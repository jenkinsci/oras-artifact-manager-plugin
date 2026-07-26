package io.jenkins.plugins.oras_artifacts;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.created;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
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

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import land.oras.ArtifactType;
import land.oras.Config;
import land.oras.Layer;
import land.oras.Manifest;
import land.oras.Registry;
import land.oras.utils.Const;
import land.oras.utils.SupportedAlgorithm;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests {@link RegistryClient} against a WireMock stand-in registry, following the same style used
 * by the ORAS Java SDK test suite ({@code RegistryWireMockTest}).
 *
 * <p>Note: {@code Registry#getDescriptor} (used to check existence and read manifests) always
 * issues a {@code HEAD} request first, followed by a {@code GET}; every manifest stub below is
 * therefore registered for both methods.
 */
@WireMockTest
class RegistryClientWireMockTest {

    private static final String REPOSITORY = "folder/my-job";
    private static final String BUILD_TAG = "1";

    private RegistryClient client(WireMockRuntimeInfo wmRuntimeInfo) {
        Registry registry = Registry.Builder.builder()
                .insecure()
                .withRegistry("localhost:%d".formatted(wmRuntimeInfo.getHttpPort()))
                .build();
        return new RegistryClient(registry);
    }

    private static String manifestPath(String tag) {
        return "/v2/%s/manifests/%s".formatted(REPOSITORY, tag);
    }

    private static String digestOf(Manifest manifest) {
        return SupportedAlgorithm.SHA256.digest(manifest.toJson().getBytes(StandardCharsets.UTF_8));
    }

    /** Registers a HEAD+GET pair returning 404 for the given manifest URL. */
    private static void stubManifestNotFound(WireMock wireMock, String path) {
        wireMock.register(head(urlEqualTo(path)).willReturn(notFound()));
        wireMock.register(get(urlEqualTo(path)).willReturn(notFound()));
    }

    /** Registers a HEAD+GET pair returning the given manifest for the given manifest URL. */
    private static void stubManifestFound(WireMock wireMock, String path, Manifest manifest) {
        String digest = digestOf(manifest);
        wireMock.register(head(urlEqualTo(path))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader(Const.CONTENT_TYPE_HEADER, Const.DEFAULT_MANIFEST_MEDIA_TYPE)
                        .withHeader(Const.DOCKER_CONTENT_DIGEST_HEADER, digest)));
        wireMock.register(get(urlEqualTo(path))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader(Const.CONTENT_TYPE_HEADER, Const.DEFAULT_MANIFEST_MEDIA_TYPE)
                        .withHeader(Const.DOCKER_CONTENT_DIGEST_HEADER, digest)
                        .withBody(manifest.toJson())));
    }

    @Test
    void ensureBuildRootCreatesRootWhenMissing(WireMockRuntimeInfo wmRuntimeInfo) {
        WireMock wireMock = wmRuntimeInfo.getWireMock();
        String scenario = "buildRoot";
        String manifestPath = manifestPath(BUILD_TAG);
        Manifest rootManifest = Manifest.empty()
                .withArtifactType(ArtifactType.from(OrasNaming.BUILD_ROOT_ARTIFACT_TYPE))
                .withConfig(Config.empty());
        String digest = digestOf(rootManifest);

        // Before creation: both HEAD and GET report the root is missing.
        wireMock.register(head(urlEqualTo(manifestPath))
                .inScenario(scenario)
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(notFound()));
        wireMock.register(get(urlEqualTo(manifestPath))
                .inScenario(scenario)
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(notFound()));

        // Blob push for the empty config: never exists, accepted via single POST.
        wireMock.register(head(urlPathMatching("/v2/.*/blobs/sha256:.*")).willReturn(notFound()));
        wireMock.register(post(urlPathMatching("/v2/.*/blobs/uploads/.*")).willReturn(created()));

        // Manifest push succeeds and moves the scenario forward.
        wireMock.register(put(urlEqualTo(manifestPath))
                .inScenario(scenario)
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(created())
                .willSetStateTo("created"));

        // After creation: both HEAD and GET report the root now exists.
        wireMock.register(head(urlEqualTo(manifestPath))
                .inScenario(scenario)
                .whenScenarioStateIs("created")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader(Const.CONTENT_TYPE_HEADER, Const.DEFAULT_MANIFEST_MEDIA_TYPE)
                        .withHeader(Const.DOCKER_CONTENT_DIGEST_HEADER, digest)));
        wireMock.register(get(urlEqualTo(manifestPath))
                .inScenario(scenario)
                .whenScenarioStateIs("created")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader(Const.CONTENT_TYPE_HEADER, Const.DEFAULT_MANIFEST_MEDIA_TYPE)
                        .withHeader(Const.DOCKER_CONTENT_DIGEST_HEADER, digest)
                        .withBody(rootManifest.toJson())));

        RegistryClient client = client(wmRuntimeInfo);
        client.ensureBuildRoot(REPOSITORY, BUILD_TAG, "folder/My Job", 1);

        wireMock.verify(WireMock.putRequestedFor(urlEqualTo(manifestPath)));
    }

    @Test
    void ensureBuildRootIsANoopWhenRootAlreadyExists(WireMockRuntimeInfo wmRuntimeInfo) {
        WireMock wireMock = wmRuntimeInfo.getWireMock();
        String manifestPath = manifestPath(BUILD_TAG);
        Manifest rootManifest =
                Manifest.empty().withArtifactType(ArtifactType.from(OrasNaming.BUILD_ROOT_ARTIFACT_TYPE));
        stubManifestFound(wireMock, manifestPath, rootManifest);

        RegistryClient client = client(wmRuntimeInfo);
        client.ensureBuildRoot(REPOSITORY, BUILD_TAG, "folder/My Job", 1);

        wireMock.verify(0, WireMock.putRequestedFor(WireMock.urlMatching(".*")));
    }

    @Test
    void listArchivedFilesParsesReferrers(WireMockRuntimeInfo wmRuntimeInfo) {
        WireMock wireMock = wmRuntimeInfo.getWireMock();
        String manifestPath = manifestPath(BUILD_TAG);

        Manifest rootManifest =
                Manifest.empty().withArtifactType(ArtifactType.from(OrasNaming.BUILD_ROOT_ARTIFACT_TYPE));
        stubManifestFound(wireMock, manifestPath, rootManifest);
        String rootDigest = digestOf(rootManifest);

        String referrersPath = "/v2/%s/referrers/%s".formatted(REPOSITORY, rootDigest);
        Manifest fileManifest = Manifest.empty()
                .withArtifactType(ArtifactType.from(OrasNaming.ARCHIVED_FILE_ARTIFACT_TYPE))
                .withAnnotations(Map.of(OrasNaming.ANNOTATION_ARCHIVED_PATH, "out/hello.txt"))
                .withLayers(List.of(Layer.fromDigest("sha256:" + "e".repeat(64), 11)
                        .withAnnotations(Map.of(Const.ANNOTATION_TITLE, "out/hello.txt"))));
        String fileDigest = digestOf(fileManifest);
        String referrersJson = """
                {"mediaType":"application/vnd.oci.image.index.v1+json","manifests":\
                [{"mediaType":"application/vnd.oci.image.manifest.v1+json",\
                "artifactType":"%s",\
                "digest":"%s","size":123}]}""".formatted(OrasNaming.ARCHIVED_FILE_ARTIFACT_TYPE, fileDigest);
        wireMock.register(get(urlPathMatching(java.util.regex.Pattern.quote(referrersPath) + ".*"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader(Const.CONTENT_TYPE_HEADER, Const.DEFAULT_INDEX_MEDIA_TYPE)
                        .withBody(referrersJson)));

        String fileManifestPath = "/v2/%s/manifests/%s".formatted(REPOSITORY, fileDigest);
        stubManifestFound(wireMock, fileManifestPath, fileManifest);

        RegistryClient client = client(wmRuntimeInfo);
        List<RegistryClient.ArchivedFile> files = client.listArchivedFiles(REPOSITORY, BUILD_TAG);

        assertEquals(1, files.size());
        assertEquals("out/hello.txt", files.get(0).path());
        assertEquals(fileDigest, files.get(0).manifestDigest());
    }

    @Test
    void listArchivedFilesReturnsEmptyWhenRootDoesNotExist(WireMockRuntimeInfo wmRuntimeInfo) {
        WireMock wireMock = wmRuntimeInfo.getWireMock();
        stubManifestNotFound(wireMock, manifestPath(BUILD_TAG));

        RegistryClient client = client(wmRuntimeInfo);
        assertTrue(client.listArchivedFiles(REPOSITORY, BUILD_TAG).isEmpty());
        assertFalse(client.exists(REPOSITORY, BUILD_TAG));
    }

    @Test
    void deleteRemovesRootAndReferrers(WireMockRuntimeInfo wmRuntimeInfo) {
        WireMock wireMock = wmRuntimeInfo.getWireMock();
        String manifestPath = manifestPath(BUILD_TAG);
        Manifest rootManifest =
                Manifest.empty().withArtifactType(ArtifactType.from(OrasNaming.BUILD_ROOT_ARTIFACT_TYPE));
        stubManifestFound(wireMock, manifestPath, rootManifest);
        String rootDigest = digestOf(rootManifest);

        String referrerDigest = "sha256:" + "b".repeat(64);
        String referrersJson = """
                {"mediaType":"application/vnd.oci.image.index.v1+json","manifests":\
                [{"mediaType":"application/vnd.oci.image.manifest.v1+json","digest":"%s","size":1}]}""".formatted(referrerDigest);
        wireMock.register(get(urlPathMatching("/v2/.*/referrers/.*"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader(Const.CONTENT_TYPE_HEADER, Const.DEFAULT_INDEX_MEDIA_TYPE)
                        .withBody(referrersJson)));

        wireMock.register(delete(urlEqualTo("/v2/%s/manifests/%s".formatted(REPOSITORY, referrerDigest)))
                .willReturn(aResponse().withStatus(202)));
        wireMock.register(
                delete(urlEqualTo(manifestPath)).willReturn(aResponse().withStatus(202)));

        RegistryClient client = client(wmRuntimeInfo);
        assertTrue(client.delete(REPOSITORY, BUILD_TAG));

        wireMock.verify(
                WireMock.deleteRequestedFor(urlEqualTo("/v2/%s/manifests/%s".formatted(REPOSITORY, referrerDigest))));
        wireMock.verify(WireMock.deleteRequestedFor(urlEqualTo(manifestPath)));
    }

    @Test
    void deleteReturnsFalseWhenNothingToDelete(WireMockRuntimeInfo wmRuntimeInfo) {
        WireMock wireMock = wmRuntimeInfo.getWireMock();
        stubManifestNotFound(wireMock, manifestPath(BUILD_TAG));

        RegistryClient client = client(wmRuntimeInfo);
        assertFalse(client.delete(REPOSITORY, BUILD_TAG));
    }

    @Test
    void pushAndPullStashRoundTrip(WireMockRuntimeInfo wmRuntimeInfo, @TempDir Path tempDir) throws IOException {
        WireMock wireMock = wmRuntimeInfo.getWireMock();
        String stashTag = OrasNaming.stashTag("my-stash");
        String manifestPath = "/v2/%s/manifests/%s".formatted(REPOSITORY, stashTag);

        wireMock.register(head(urlPathMatching("/v2/.*/blobs/sha256:.*")).willReturn(notFound()));
        wireMock.register(post(urlPathMatching("/v2/.*/blobs/uploads/.*")).willReturn(created()));
        wireMock.register(put(urlEqualTo(manifestPath)).willReturn(created()));

        Path tarGz = Files.createFile(tempDir.resolve("stash.tar.gz"));
        Files.writeString(tarGz, "fake-tar-gz-content");
        byte[] content = Files.readAllBytes(tarGz);
        String contentDigest = SupportedAlgorithm.SHA256.digest(content);

        Manifest stashManifest = Manifest.empty()
                .withArtifactType(ArtifactType.from(OrasNaming.STASH_ARTIFACT_TYPE))
                .withLayers(
                        List.of(Layer.fromDigest(contentDigest, content.length).withMediaType("application/gzip")));
        stubManifestFound(wireMock, manifestPath, stashManifest);

        wireMock.register(get(urlEqualTo("/v2/%s/blobs/%s".formatted(REPOSITORY, contentDigest)))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader(Const.DOCKER_CONTENT_DIGEST_HEADER, contentDigest)
                        .withBody(content)));

        RegistryClient client = client(wmRuntimeInfo);
        client.pushStash(REPOSITORY, "my-stash", tarGz);
        assertTrue(client.hasStash(REPOSITORY, "my-stash"));

        Path downloaded = tempDir.resolve("downloaded.tar.gz");
        client.pullStash(REPOSITORY, "my-stash", downloaded);
        assertEquals("fake-tar-gz-content", Files.readString(downloaded));
    }

    @Test
    void openFileFetchesLayerBlob(WireMockRuntimeInfo wmRuntimeInfo) throws IOException {
        WireMock wireMock = wmRuntimeInfo.getWireMock();
        String manifestPath = manifestPath(BUILD_TAG);
        Manifest rootManifest =
                Manifest.empty().withArtifactType(ArtifactType.from(OrasNaming.BUILD_ROOT_ARTIFACT_TYPE));
        stubManifestFound(wireMock, manifestPath, rootManifest);

        String layerDigest = SupportedAlgorithm.SHA256.digest("hello world".getBytes(StandardCharsets.UTF_8));
        Manifest fileManifest = Manifest.empty()
                .withArtifactType(ArtifactType.from(OrasNaming.ARCHIVED_FILE_ARTIFACT_TYPE))
                .withAnnotations(Map.of(OrasNaming.ANNOTATION_ARCHIVED_PATH, "hello.txt"))
                .withLayers(List.of(Layer.fromDigest(layerDigest, 11)));
        String fileDigest = digestOf(fileManifest);
        String referrersJson = """
                {"mediaType":"application/vnd.oci.image.index.v1+json","manifests":\
                [{"mediaType":"application/vnd.oci.image.manifest.v1+json",\
                "artifactType":"%s","digest":"%s","size":1}]}""".formatted(OrasNaming.ARCHIVED_FILE_ARTIFACT_TYPE, fileDigest);
        wireMock.register(get(urlPathMatching("/v2/.*/referrers/.*"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader(Const.CONTENT_TYPE_HEADER, Const.DEFAULT_INDEX_MEDIA_TYPE)
                        .withBody(referrersJson)));

        stubManifestFound(wireMock, "/v2/%s/manifests/%s".formatted(REPOSITORY, fileDigest), fileManifest);

        wireMock.register(get(urlEqualTo("/v2/%s/blobs/%s".formatted(REPOSITORY, layerDigest)))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader(Const.DOCKER_CONTENT_DIGEST_HEADER, layerDigest)
                        .withBody("hello world")));

        RegistryClient client = client(wmRuntimeInfo);
        try (InputStream is = client.openFile(REPOSITORY, BUILD_TAG, "hello.txt")) {
            assertEquals("hello world", new String(is.readAllBytes()));
        }
    }
}
