package io.jenkins.plugins.oras_artifacts;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import land.oras.Annotations;
import land.oras.ArtifactType;
import land.oras.Config;
import land.oras.ContainerRef;
import land.oras.Descriptor;
import land.oras.Layer;
import land.oras.LocalPath;
import land.oras.Manifest;
import land.oras.ManifestDescriptor;
import land.oras.OCI;
import land.oras.Referrers;
import land.oras.Registry;
import land.oras.exception.OrasException;
import land.oras.utils.Const;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wraps a {@link Registry}
 */
@Restricted(NoExternalUse.class)
public final class RegistryClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(RegistryClient.class);

    private final Registry registry;

    RegistryClient(@NonNull Registry registry) {
        this.registry = registry;
    }

    /**
     * A file archived as a referrer of a build root artifact.
     * @param path the archived path, relative to the artifacts root
     * @param manifestDigest the digest of the file's own (referrer) manifest
     * @param layer the single content layer of the file
     */
    public record ArchivedFile(String path, String manifestDigest, Layer layer) {}

    private ContainerRef ref(String repository, String tag) {
        return ContainerRef.parse("%s:%s".formatted(repository, tag)).forRegistry(registry);
    }

    private ContainerRef repositoryRef(String repository) {
        return ContainerRef.parse(repository).forRegistry(registry);
    }

    /**
     * Return whether a manifest exists for the given repository/tag.
     */
    public boolean exists(String repository, String tag) {
        try {
            registry.getDescriptor(ref(repository, tag));
            return true;
        } catch (OrasException e) {
            if (isNotFound(e)) {
                return false;
            }
            throw e;
        }
    }

    /**
     * Ensure the build root artifact exists for the given repository/tag, creating it if needed.
     */
    public void ensureBuildRoot(String repository, String tag, String jobFullName, int buildNumber) {
        ContainerRef ref = ref(repository, tag);
        if (exists(repository, tag)) {
            return;
        }
        Annotations annotations = Annotations.ofManifest(new HashMap<>(Map.of(
                OrasNaming.ANNOTATION_JOB_FULL_NAME,
                jobFullName,
                OrasNaming.ANNOTATION_BUILD_NUMBER,
                String.valueOf(buildNumber))));
        registry.pushArtifact(
                ref,
                ArtifactType.from(OrasNaming.BUILD_ROOT_ARTIFACT_TYPE),
                annotations,
                Config.empty(),
                OCI.PushOptions.defaults());
        LOGGER.debug("Created build root artifact {}", ref);
    }

    /**
     * Attach a single archived file as a referrer of the build root artifact.
     * @param repository the repository (sanitized job full name)
     * @param tag the build root tag
     * @param archivedPath the archived path, relative to the artifacts root (e.g. {@code "dir/file.txt"})
     * @param localFile the local file to upload
     */
    @SuppressFBWarnings("NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE")
    public void archiveFile(String repository, String tag, String archivedPath, Path localFile) {
        ContainerRef ref = ref(repository, tag);
        String fileName = localFile.getFileName().toString();
        Annotations annotations = Annotations.ofManifest(
                        new HashMap<>(Map.of(OrasNaming.ANNOTATION_ARCHIVED_PATH, archivedPath)))
                .withFileAnnotations(fileName, Map.of(Const.ANNOTATION_TITLE, archivedPath));
        registry.attachArtifact(
                ref, ArtifactType.from(OrasNaming.ARCHIVED_FILE_ARTIFACT_TYPE), annotations, LocalPath.of(localFile));
    }

    /**
     * List all files archived for the given repository/tag by querying the OCI referrers API of
     * the build root artifact digest.
     */
    public List<ArchivedFile> listArchivedFiles(String repository, String tag) {
        List<ArchivedFile> files = new ArrayList<>();
        ContainerRef ref = ref(repository, tag);
        Descriptor rootDescriptor;
        try {
            rootDescriptor = registry.getDescriptor(ref);
        } catch (OrasException e) {
            if (isNotFound(e)) {
                return files;
            }
            throw e;
        }
        ContainerRef digestRef = ref.withDigest(rootDescriptor.getDigest());
        Referrers referrers =
                registry.getReferrers(digestRef, ArtifactType.from(OrasNaming.ARCHIVED_FILE_ARTIFACT_TYPE));
        for (ManifestDescriptor referrer : referrers.getManifests()) {
            Manifest manifest = registry.getManifest(ref.withDigest(referrer.getDigest()));
            if (manifest.getLayers().isEmpty()) {
                continue;
            }
            String path = manifest.getAnnotations().get(OrasNaming.ANNOTATION_ARCHIVED_PATH);
            Layer layer = manifest.getLayers().get(0);
            if (path == null) {
                path = layer.getAnnotations().get(Const.ANNOTATION_TITLE);
            }
            if (path == null) {
                continue;
            }
            files.add(new ArchivedFile(path, referrer.getDigest(), layer));
        }
        return files;
    }

    /**
     * Find a single archived file by its path.
     */
    public Optional<ArchivedFile> findArchivedFile(String repository, String tag, String path) {
        return listArchivedFiles(repository, tag).stream()
                .filter(f -> f.path().equals(path))
                .findFirst();
    }

    /**
     * Open a stream to download the content of an archived file.
     */
    public InputStream openFile(String repository, String tag, String path) throws IOException {
        ArchivedFile file = findArchivedFile(repository, tag, path)
                .orElseThrow(() -> new IOException("No such archived file: " + path));
        ContainerRef ref = ref(repository, tag);
        return registry.fetchBlob(ref.withDigest(file.layer().getDigest()));
    }

    /**
     * Delete the build root artifact and all its referrers.
     * @return {@code true} if something was deleted
     */
    public boolean delete(String repository, String tag) {
        ContainerRef ref = ref(repository, tag);
        Descriptor rootDescriptor;
        try {
            rootDescriptor = registry.getDescriptor(ref);
        } catch (OrasException e) {
            if (isNotFound(e)) {
                return false;
            }
            throw e;
        }
        ContainerRef digestRef = ref.withDigest(rootDescriptor.getDigest());
        Referrers referrers = registry.getReferrers(digestRef, null);
        for (ManifestDescriptor referrer : referrers.getManifests()) {
            try {
                registry.deleteManifest(ref.withDigest(referrer.getDigest()));
            } catch (OrasException e) {
                LOGGER.warn("Failed to delete referrer {} of {}", referrer.getDigest(), ref, e);
            }
        }
        registry.deleteManifest(ref);
        return true;
    }

    /**
     * Push a stash (a single tar.gz artifact) under the given repository/name.
     */
    public void pushStash(String repository, String name, Path tarGz) {
        ContainerRef ref = ref(repository, OrasNaming.stashTag(name));
        registry.pushArtifact(
                ref,
                ArtifactType.from(OrasNaming.STASH_ARTIFACT_TYPE),
                Annotations.empty(),
                Config.empty(),
                OCI.PushOptions.defaults(),
                LocalPath.of(tarGz, "application/gzip"));
    }

    /**
     * Whether a stash exists.
     */
    public boolean hasStash(String repository, String name) {
        return exists(repository, OrasNaming.stashTag(name));
    }

    /**
     * Pull the content of a stash to the given target file.
     */
    public void pullStash(String repository, String name, Path target) {
        ContainerRef ref = ref(repository, OrasNaming.stashTag(name));
        Manifest manifest = registry.getManifest(ref);
        if (manifest.getLayers().isEmpty()) {
            throw new OrasException("Stash manifest doesn't contain any layer");
        }
        Layer layer = manifest.getLayers().get(0);
        registry.fetchBlob(ref.withDigest(layer.getDigest()), target);
    }

    /**
     * Delete all stashes (tags starting with {@code stash-}) for the given repository.
     */
    public void deleteAllStashes(String repository) {
        List<String> tags;
        try {
            tags = registry.getTags(repositoryRef(repository)).tags();
        } catch (OrasException e) {
            if (isNotFound(e)) {
                return;
            }
            throw e;
        }
        for (String tag : tags) {
            if (tag.startsWith(OrasNaming.STASH_TAG_PREFIX)) {
                try {
                    registry.deleteManifest(ref(repository, tag));
                } catch (OrasException e) {
                    LOGGER.warn("Failed to delete stash tag {} in {}", tag, repository, e);
                }
            }
        }
    }

    /**
     * Test the connection to the registry by pushing and deleting a small artifact.
     */
    public void testConnection(String repository) throws IOException {
        Path tmpFile = Files.createTempFile("oras-artifact-manager-test-", ".txt");
        try {
            Files.writeString(tmpFile, "oras-artifact-manager-connection-test");
            ContainerRef ref = ref(repository, "connection-test");
            registry.pushArtifact(
                    ref,
                    ArtifactType.unknown(),
                    Annotations.empty(),
                    Config.empty(),
                    OCI.PushOptions.defaults(),
                    LocalPath.of(tmpFile));
            registry.deleteManifest(ref);
        } finally {
            Files.deleteIfExists(tmpFile);
        }
    }

    private static boolean isNotFound(OrasException e) {
        return Integer.valueOf(404).equals(e.getStatusCode());
    }
}
