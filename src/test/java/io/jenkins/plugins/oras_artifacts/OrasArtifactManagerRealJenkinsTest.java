package io.jenkins.plugins.oras_artifacts;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.created;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.head;
import static com.github.tomakehurst.wiremock.client.WireMock.notFound;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.extension.ResponseTransformerV2;
import com.github.tomakehurst.wiremock.http.HttpHeader;
import com.github.tomakehurst.wiremock.http.HttpHeaders;
import com.github.tomakehurst.wiremock.http.Response;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import java.io.ByteArrayOutputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.zip.GZIPOutputStream;
import jenkins.model.ArtifactManagerConfiguration;
import land.oras.ArtifactType;
import land.oras.Config;
import land.oras.Layer;
import land.oras.Manifest;
import land.oras.utils.Const;
import land.oras.utils.SupportedAlgorithm;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.RealJenkinsExtension;

/**
 * End-to-end test using real jenkins rule
 */
class OrasArtifactManagerRealJenkinsTest {

    @RegisterExtension
    private final RealJenkinsExtension extension =
            new RealJenkinsExtension().withLogger("io.jenkins.plugins.oras_artifacts", Level.FINE);

    private WireMockServer wireMockServer;

    /**
     * Ensure to echo the manifest digest for all manifest response (validated by ORAS java SDK)
     */
    private static final class EchoDigestManifestTransformer implements ResponseTransformerV2 {

        private final Map<String, byte[]> manifestsByPath = new ConcurrentHashMap<>();

        @Override
        public String getName() {
            return "echo-digest-manifest";
        }

        @Override
        public boolean applyGlobally() {
            return true;
        }

        @Override
        public Response transform(Response response, ServeEvent serveEvent) {
            String path = serveEvent.getRequest().getUrl();
            if (!path.matches("/v2/.*/manifests/sha256:.*")) {
                return response;
            }
            String method = serveEvent.getRequest().getMethod().getName();
            if ("PUT".equals(method)) {
                manifestsByPath.put(path, serveEvent.getRequest().getBody());
                return response;
            }
            byte[] body = manifestsByPath.get(path);
            if (body == null) {
                return response;
            }
            String digest = path.substring(path.lastIndexOf("sha256:"));
            HttpHeaders headers = new HttpHeaders(
                    new HttpHeader(Const.CONTENT_TYPE_HEADER, Const.DEFAULT_MANIFEST_MEDIA_TYPE),
                    new HttpHeader(Const.DOCKER_CONTENT_DIGEST_HEADER, digest));
            Response.Builder builder =
                    Response.Builder.like(response).status(200).headers(headers);
            if ("GET".equals(method)) {
                builder = builder.body(body);
            }
            return builder.build();
        }
    }

    @BeforeEach
    void startWireMock() {
        wireMockServer = new WireMockServer(
                WireMockConfiguration.options().dynamicPort().extensions(new EchoDigestManifestTransformer()));
        wireMockServer.start();
    }

    @AfterEach
    void stopWireMock() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    private static final String REPOSITORY = "archive-it";
    private static final String BUILD_TAG = "1";

    /**
     * Registers stubs covering the full HTTP call sequence performed by {@link OrasArtifactManager}
     * when archiving a build with a single file: creating the build root artifact, then attaching
     * the file as a referrer.
     */
    private void stubArchiveFlow() {
        String manifestPath = "/v2/%s/manifests/%s".formatted(REPOSITORY, BUILD_TAG);
        Manifest rootManifest = Manifest.empty()
                .withArtifactType(ArtifactType.from(OrasNaming.BUILD_ROOT_ARTIFACT_TYPE))
                .withConfig(Config.empty());
        String rootDigest =
                SupportedAlgorithm.SHA256.digest(rootManifest.toJson().getBytes(StandardCharsets.UTF_8));
        String scenario = "archive";

        // 1) ensureBuildRoot: root doesn't exist yet.
        wireMockServer.stubFor(head(urlEqualTo(manifestPath))
                .inScenario(scenario)
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(notFound()));
        wireMockServer.stubFor(get(urlEqualTo(manifestPath))
                .inScenario(scenario)
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(notFound()));

        // Any blob (config or file content) is accepted via a single POST upload.
        wireMockServer.stubFor(head(urlPathMatching("/v2/.*/blobs/sha256:.*")).willReturn(notFound()));
        wireMockServer.stubFor(post(urlPathMatching("/v2/.*/blobs/uploads/.*")).willReturn(created()));

        // Root manifest push succeeds and moves the scenario forward.
        wireMockServer.stubFor(put(urlEqualTo(manifestPath))
                .inScenario(scenario)
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(created())
                .willSetStateTo("root-created"));

        // 2) Once created, HEAD/GET report the root artifact exists (needed both by
        // RegistryClient#exists and internally by OCI#attachArtifact to resolve the subject).
        wireMockServer.stubFor(head(urlEqualTo(manifestPath))
                .inScenario(scenario)
                .whenScenarioStateIs("root-created")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader(Const.CONTENT_TYPE_HEADER, Const.DEFAULT_MANIFEST_MEDIA_TYPE)
                        .withHeader(Const.DOCKER_CONTENT_DIGEST_HEADER, rootDigest)));
        wireMockServer.stubFor(get(urlEqualTo(manifestPath))
                .inScenario(scenario)
                .whenScenarioStateIs("root-created")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader(Const.CONTENT_TYPE_HEADER, Const.DEFAULT_MANIFEST_MEDIA_TYPE)
                        .withHeader(Const.DOCKER_CONTENT_DIGEST_HEADER, rootDigest)
                        .withBody(rootManifest.toJson())));

        // 3) The file referrer manifest is pushed to, and re-read from, a digest-addressed path.
        // Its content is captured and echoed back by EchoDigestManifestTransformer so that the
        // client's digest verification succeeds regardless of the exact bytes pushed.
        wireMockServer.stubFor(put(urlPathMatching("/v2/.*/manifests/sha256:.*"))
                .willReturn(aResponse().withStatus(201).withHeader(Const.OCI_SUBJECT_HEADER, rootDigest)));
        wireMockServer.stubFor(head(urlPathMatching("/v2/.*/manifests/sha256:.*"))
                .willReturn(aResponse().withStatus(200)));
        wireMockServer.stubFor(get(urlPathMatching("/v2/.*/manifests/sha256:.*"))
                .willReturn(aResponse().withStatus(200)));
    }

    @Test
    void archiveArtifactsPipelineSucceeds() throws Throwable {
        stubArchiveFlow();
        extension.then(new ArchiveStep(wireMockServer.port()));

        // Verify the expected HTTP call sequence actually reached the (real, separate-process)
        // registry stand-in: the build root was created and the archived file was attached as a
        // referrer manifest.
        wireMockServer.verify(
                1, WireMock.putRequestedFor(urlEqualTo("/v2/%s/manifests/%s".formatted(REPOSITORY, BUILD_TAG))));
        wireMockServer.verify(1, WireMock.putRequestedFor(urlPathMatching("/v2/.*/manifests/sha256:.*")));
    }

    private record ArchiveStep(int wireMockPort) implements RealJenkinsExtension.Step, Serializable {
        @Override
        public void run(JenkinsRule rule) throws Throwable {
            OrasGenericArtifactConfig config =
                    new OrasGenericArtifactConfig("localhost:%d".formatted(wireMockPort), null, null, true);
            ArtifactManagerConfiguration.get()
                    .getArtifactManagerFactories()
                    .add(new OrasArtifactManagerFactory(config));

            WorkflowJob job = rule.jenkins.createProject(WorkflowJob.class, "archive-it");
            job.setDefinition(new CpsFlowDefinition("""
                    pipeline {
                        agent any
                        stages {
                            stage('Archive') {
                                steps {
                                    writeFile file: 'out.txt', text: 'hello from oras-artifact-manager\\n'
                                    archiveArtifacts artifacts: 'out.txt'
                                }
                            }
                        }
                    }
                    """, true));
            rule.buildAndAssertSuccess(job);
        }
    }

    private static final String STASH_NAME = "my-stash";
    private static final String STASHED_FILE_CONTENT = "canned content served back by the stand-in registry\n";

    /**
     * Build a valid tar.gz archive (as {@code stash}/{@code unstash} expect) containing a single
     * file, {@code to-stash.txt}, with a fixed, recognizable content. Since the stand-in registry
     * doesn't actually persist what gets pushed, this canned archive is what {@code unstash} will
     * always extract - the test asserts on this fixed content to prove the round trip works.
     */
    private static byte[] buildStashTarGz() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes);
                TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
            byte[] content = STASHED_FILE_CONTENT.getBytes(StandardCharsets.UTF_8);
            TarArchiveEntry entry = new TarArchiveEntry("to-stash.txt");
            entry.setSize(content.length);
            tar.putArchiveEntry(entry);
            tar.write(content);
            tar.closeArchiveEntry();
        }
        return bytes.toByteArray();
    }

    /**
     * Registers stubs covering the full HTTP call sequence performed by {@link OrasArtifactManager}
     * when stashing and then unstashing a single file.
     */
    private void stubStashFlow() throws Exception {
        String stashTag = OrasNaming.stashTag(STASH_NAME);
        String manifestPath = "/v2/%s/manifests/%s".formatted(REPOSITORY, stashTag);

        // Pushing the stash: blob push is accepted, manifest push is accepted.
        wireMockServer.stubFor(head(urlPathMatching("/v2/.*/blobs/sha256:.*")).willReturn(notFound()));
        wireMockServer.stubFor(post(urlPathMatching("/v2/.*/blobs/uploads/.*")).willReturn(created()));
        wireMockServer.stubFor(put(urlEqualTo(manifestPath)).willReturn(created()));

        // Unstashing: the manifest read back references a single tar.gz layer, whose content is the
        // canned archive built above.
        byte[] tarGz = buildStashTarGz();
        String contentDigest = SupportedAlgorithm.SHA256.digest(tarGz);
        Manifest stashManifest = Manifest.empty()
                .withArtifactType(ArtifactType.from(OrasNaming.STASH_ARTIFACT_TYPE))
                .withLayers(java.util.List.of(
                        Layer.fromDigest(contentDigest, tarGz.length).withMediaType("application/gzip")));
        String stashManifestDigest =
                SupportedAlgorithm.SHA256.digest(stashManifest.toJson().getBytes(StandardCharsets.UTF_8));
        wireMockServer.stubFor(head(urlEqualTo(manifestPath))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader(Const.CONTENT_TYPE_HEADER, Const.DEFAULT_MANIFEST_MEDIA_TYPE)
                        .withHeader(Const.DOCKER_CONTENT_DIGEST_HEADER, stashManifestDigest)));
        wireMockServer.stubFor(get(urlEqualTo(manifestPath))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader(Const.CONTENT_TYPE_HEADER, Const.DEFAULT_MANIFEST_MEDIA_TYPE)
                        .withHeader(Const.DOCKER_CONTENT_DIGEST_HEADER, stashManifestDigest)
                        .withBody(stashManifest.toJson())));
        wireMockServer.stubFor(get(urlEqualTo("/v2/%s/blobs/%s".formatted(REPOSITORY, contentDigest)))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader(Const.DOCKER_CONTENT_DIGEST_HEADER, contentDigest)
                        .withBody(tarGz)));
    }

    @Test
    void stashAndUnstashPipelineSucceeds() throws Throwable {
        stubStashFlow();
        extension.then(new StashStep(wireMockServer.port()));

        String stashTag = OrasNaming.stashTag(STASH_NAME);
        wireMockServer.verify(
                1, WireMock.putRequestedFor(urlEqualTo("/v2/%s/manifests/%s".formatted(REPOSITORY, stashTag))));
        // At least once: RegistryClient#hasStash (existence check before unstashing) and
        // RegistryClient#pullStash (to read the manifest) both GET this same tag.
        wireMockServer.verify(
                WireMock.moreThanOrExactly(1),
                WireMock.getRequestedFor(urlEqualTo("/v2/%s/manifests/%s".formatted(REPOSITORY, stashTag))));
    }

    private record StashStep(int wireMockPort) implements RealJenkinsExtension.Step, Serializable {
        @Override
        public void run(JenkinsRule rule) throws Throwable {
            OrasGenericArtifactConfig config =
                    new OrasGenericArtifactConfig("localhost:%d".formatted(wireMockPort), null, null, true);
            ArtifactManagerConfiguration.get()
                    .getArtifactManagerFactories()
                    .add(new OrasArtifactManagerFactory(config));

            WorkflowJob job = rule.jenkins.createProject(WorkflowJob.class, REPOSITORY);
            job.setDefinition(
                    new CpsFlowDefinition("""
                    pipeline {
                        agent any
                        stages {
                            stage('Stash') {
                                steps {
                                    writeFile file: 'to-stash.txt', text: 'original content before stash\\n'
                                    stash name: '%s', includes: 'to-stash.txt'
                                }
                            }
                            stage('Unstash') {
                                steps {
                                    deleteDir()
                                    unstash '%s'
                                    script {
                                        def content = readFile('to-stash.txt').trim()
                                        if (!content.equals('%s')) {
                                            error("Unexpected content after unstash: '${content}'")
                                        }
                                    }
                                }
                            }
                        }
                    }
                    """.formatted(STASH_NAME, STASH_NAME, STASHED_FILE_CONTENT.trim()), true));
            rule.buildAndAssertSuccess(job);
        }
    }
}
