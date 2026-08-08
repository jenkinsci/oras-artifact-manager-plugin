package io.jenkins.plugins.oras_artifacts;

import java.io.Serial;
import java.io.Serializable;
import net.sf.json.JSONObject;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * The information shown to a user for a single archived file: the OCI reference
 *
 * @param registryUrl the registry host (and optional port), e.g. {@code "registry.example.com"}
 * @param repository the OCI repository (sanitized job full name)
 * @param tag the OCI tag of the build's root artifact
 * @param manifestDigest the digest of the file's own referrer manifest
 * @param mediaType the media type of the file's content layer
 * @param size the size in bytes of the file's content layer, or {@code null} if unknown
 * @param reference the full reference, e.g. {@code "registry.example.com/repo@sha256:..."}
 */
record OrasArtifactReference(
        String registryUrl,
        String repository,
        String tag,
        String manifestDigest,
        String mediaType,
        Long size,
        String reference)
        implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Build the reference for a single archived file.
     */
    @Restricted(NoExternalUse.class)
    static OrasArtifactReference of(
            String registryUrl, String repository, String tag, RegistryClient.ArchivedFile file) {
        String reference = "%s/%s@%s".formatted(registryUrl, repository, file.manifestDigest());
        return new OrasArtifactReference(
                registryUrl,
                repository,
                tag,
                file.manifestDigest(),
                file.layer().getMediaType(),
                file.layer().getSize(),
                reference);
    }

    /**
     * The suggested command to reproduce this file locally.
     */
    String pullCommand() {
        return "oras pull %s".formatted(reference);
    }

    JSONObject toJson() {
        JSONObject json = new JSONObject();
        json.put("registryUrl", registryUrl);
        json.put("repository", repository);
        json.put("tag", tag);
        json.put("manifestDigest", manifestDigest);
        json.put("mediaType", mediaType);
        json.put("size", size);
        json.put("reference", reference);
        json.put("pullCommand", pullCommand());
        return json;
    }
}
