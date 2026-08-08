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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.extension.ResponseTransformerV2;
import com.github.tomakehurst.wiremock.http.HttpHeader;
import com.github.tomakehurst.wiremock.http.HttpHeaders;
import com.github.tomakehurst.wiremock.http.Response;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import jenkins.model.ArtifactManagerConfiguration;
import land.oras.ArtifactType;
import land.oras.Config;
import land.oras.Manifest;
import land.oras.utils.Const;
import land.oras.utils.SupportedAlgorithm;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.DomNode;
import org.htmlunit.html.DomNodeList;
import org.htmlunit.html.HtmlPage;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.RealJenkinsExtension;

/**
 * End-to-end UI test
 */
class OrasArtifactReferenceUiRealJenkinsTest {

    @RegisterExtension
    private final RealJenkinsExtension extension =
            new RealJenkinsExtension().withLogger("io.jenkins.plugins.oras_artifacts", Level.FINE);

    private WireMockServer wireMockServer;

    private static final String REPOSITORY = "reference-ui-it";
    private static final String BUILD_TAG = "1";
    private static final String ARCHIVED_FILE = "out.txt";

    /**
     * Ensure to echo the manifest digest for all manifest response (validated by ORAS java SDK)
     * Extended for referrers as wekk
     */
    private static final class EchoDigestManifestAndReferrersTransformer implements ResponseTransformerV2 {

        private final Map<String, byte[]> manifestsByPath = new ConcurrentHashMap<>();
        private final List<String> capturedDigests = new CopyOnWriteArrayList<>();

        @Override
        public String getName() {
            return "echo-digest-manifest-and-referrers";
        }

        @Override
        public Response transform(Response response, ServeEvent serveEvent) {
            String path = serveEvent.getRequest().getUrl();
            String method = serveEvent.getRequest().getMethod().getName();

            if (path.matches("/v2/.*/manifests/sha256:.*")) {
                if ("PUT".equals(method)) {
                    manifestsByPath.put(path, serveEvent.getRequest().getBody());
                    capturedDigests.add(path.substring(path.lastIndexOf("sha256:")));
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

            if (path.matches("/v2/.*/referrers/sha256:.*") && "GET".equals(method)) {
                StringBuilder manifests = new StringBuilder();
                for (String digest : capturedDigests) {
                    if (manifests.length() > 0) {
                        manifests.append(',');
                    }
                    manifests.append("""
                            {"mediaType":"application/vnd.oci.image.manifest.v1+json",\
                            "artifactType":"%s","digest":"%s","size":1}""".formatted(OrasNaming.ARCHIVED_FILE_ARTIFACT_TYPE, digest));
                }
                String index = """
                        {"mediaType":"application/vnd.oci.image.index.v1+json","manifests":[%s]}""".formatted(manifests);
                return Response.Builder.like(response)
                        .status(200)
                        .headers(new HttpHeaders(
                                new HttpHeader(Const.CONTENT_TYPE_HEADER, Const.DEFAULT_INDEX_MEDIA_TYPE)))
                        .body(index)
                        .build();
            }

            return response;
        }
    }

    @BeforeEach
    void startWireMock() {
        wireMockServer = new WireMockServer(WireMockConfiguration.options()
                .dynamicPort()
                .extensions(new EchoDigestManifestAndReferrersTransformer()));
        wireMockServer.start();
    }

    @AfterEach
    void stopWireMock() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    private void stubArchiveFlow() {
        String manifestPath = "/v2/%s/manifests/%s".formatted(REPOSITORY, BUILD_TAG);
        Manifest rootManifest = Manifest.empty()
                .withArtifactType(ArtifactType.from(OrasNaming.BUILD_ROOT_ARTIFACT_TYPE))
                .withConfig(Config.empty());
        String rootDigest =
                SupportedAlgorithm.SHA256.digest(rootManifest.toJson().getBytes(StandardCharsets.UTF_8));
        String scenario = "archive";

        wireMockServer.stubFor(head(urlEqualTo(manifestPath))
                .inScenario(scenario)
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(notFound()));
        wireMockServer.stubFor(get(urlEqualTo(manifestPath))
                .inScenario(scenario)
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(notFound()));

        wireMockServer.stubFor(head(urlPathMatching("/v2/.*/blobs/sha256:.*")).willReturn(notFound()));
        wireMockServer.stubFor(post(urlPathMatching("/v2/.*/blobs/uploads/.*")).willReturn(created()));

        wireMockServer.stubFor(put(urlEqualTo(manifestPath))
                .inScenario(scenario)
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(created())
                .willSetStateTo("root-created"));

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

        wireMockServer.stubFor(put(urlPathMatching("/v2/.*/manifests/sha256:.*"))
                .willReturn(aResponse().withStatus(201).withHeader(Const.OCI_SUBJECT_HEADER, rootDigest)));
        wireMockServer.stubFor(head(urlPathMatching("/v2/.*/manifests/sha256:.*"))
                .willReturn(aResponse().withStatus(200)));
        wireMockServer.stubFor(get(urlPathMatching("/v2/.*/manifests/sha256:.*"))
                .willReturn(aResponse().withStatus(200)));

        wireMockServer.stubFor(get(urlPathMatching("/v2/.*/referrers/.*"))
                .willReturn(aResponse().withStatus(200)));
    }

    @Test
    void archivedFileIsDecoratedWithReferenceIcon() throws Throwable {
        stubArchiveFlow();
        extension.then(new ArchiveAndCheckIconStep(wireMockServer.port()));
    }

    private record ArchiveAndCheckIconStep(int wireMockPort) implements RealJenkinsExtension.Step, Serializable {

        @Override
        public void run(JenkinsRule rule) throws Throwable {
            OrasGenericArtifactConfig config =
                    new OrasGenericArtifactConfig("localhost:%d".formatted(wireMockPort), null, null, true);
            ArtifactManagerConfiguration.get()
                    .getArtifactManagerFactories()
                    .add(new OrasArtifactManagerFactory(config));

            WorkflowJob job = rule.jenkins.createProject(WorkflowJob.class, REPOSITORY);
            job.setDefinition(new CpsFlowDefinition("""
                    pipeline {
                        agent any
                        stages {
                            stage('Archive') {
                                steps {
                                    writeFile file: '%s', text: 'hello from oras-artifact-manager\\n'
                                    archiveArtifacts artifacts: '%s'
                                }
                            }
                        }
                    }
                    """.formatted(ARCHIVED_FILE, ARCHIVED_FILE), true));
            rule.buildAndAssertSuccess(job);

            // The JSON endpoint itself resolves the expected reference.
            try (JenkinsRule.WebClient webClient = rule.createWebClient()) {
                org.htmlunit.Page response = webClient.goTo(
                        job.getUrl() + "1/oras-artifact-manager/reference?path=" + ARCHIVED_FILE, "application/json");
                assertEquals(200, response.getWebResponse().getStatusCode());
                String body = response.getWebResponse().getContentAsString();
                assertTrue(body.contains("localhost:%d/%s@sha256:".formatted(wireMockPort, REPOSITORY)), body);
            }

            // And the build page's artifact list is decorated with the reference icon.
            try (JenkinsRule.WebClient webClient = rule.createWebClient()) {
                HtmlPage buildPage = webClient.goTo(job.getUrl() + "1/");
                // Wait for Jenkins' async artifact list fetch, the fallback setTimeout scan,
                // and the subsequent fetch() calls to the reference endpoint to all complete.
                webClient.waitForBackgroundJavaScript(15_000);

                assertReferenceIcon(buildPage, wireMockPort);
            }

            // The job overview page's "Last Successful Artifacts" summary is decorated too.
            try (JenkinsRule.WebClient webClient = rule.createWebClient()) {
                HtmlPage jobPage = webClient.goTo(job.getUrl());
                webClient.waitForBackgroundJavaScript(15_000);

                assertReferenceIcon(jobPage, wireMockPort);
            }
        }

        private void assertReferenceIcon(HtmlPage page, int wireMockPort) {
            DomNodeList<DomNode> icons = page.querySelectorAll(".oras-artifact-manager-icon");
            assertEquals(1, icons.size(), "Expected exactly one archived file to be decorated");

            DomElement icon = (DomElement) icons.get(0);
            assertEquals("svg", icon.getTagName());
            assertFalse(
                    icon.getAttribute("class").contains("jenkins-visually-hidden"),
                    "Icon must not be the visually-hidden accessibility helper");
            assertTrue(icon.getAttribute("class").contains("icon-sm"), icon.getAttribute("class"));

            DomNode previousSibling = icon.getPreviousSibling();
            assertTrue(previousSibling instanceof DomElement, "Icon must have a preceding sibling element");
            DomElement previousElement = (DomElement) previousSibling;
            assertEquals("a", previousElement.getTagName());
            assertTrue(previousElement.getAttribute("href").endsWith("/*view*/"), previousElement.getAttribute("href"));

            String expectedReference = "localhost:%d/%s@sha256:".formatted(wireMockPort, REPOSITORY);
            String tooltip = icon.getAttribute("tooltip");
            assertTrue(tooltip.contains(expectedReference), tooltip);
            assertFalse(tooltip.contains("oras pull"), tooltip);

            String style = icon.getAttribute("style");
            assertTrue(style.contains("--text-color"), style);
            assertFalse(style.contains("--link-color"), style);
            assertTrue(style.contains("important"), style);
        }
    }
}
