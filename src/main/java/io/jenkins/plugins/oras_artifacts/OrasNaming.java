package io.jenkins.plugins.oras_artifacts;

import java.util.Arrays;
import java.util.regex.Pattern;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Utility helpers turning Jenkins names (job full names, build numbers, stash names) into
 * OCI-spec compliant repository/tag names.
 *
 * <p>The convention used by this plugin is:
 * <ul>
 *     <li>The OCI <b>repository</b> matches the sanitized job full name (folders included).</li>
 *     <li>The <b>tag</b> for archived build artifacts is the build number (e.g. {@code 9}).</li>
 *     <li>The <b>tag</b> for a stash is {@code stash-<name>}.</li>
 * </ul>
 */
@Restricted(NoExternalUse.class)
final class OrasNaming {

    /** Prefix used for the tag of a stash. */
    static final String STASH_TAG_PREFIX = "stash-";

    /** Media type of the root artifact tagged for a build. It has no layers, only referrers. */
    static final String BUILD_ROOT_ARTIFACT_TYPE = "application/vnd.io.jenkins.oras-artifact-manager.build.v1+json";

    /** Media type of an artifact manifest attached (as a referrer) to the build root for a single archived file. */
    static final String ARCHIVED_FILE_ARTIFACT_TYPE = "application/vnd.io.jenkins.oras-artifact-manager.file.v1+json";

    /** Media type of a stash artifact (a single tar.gz layer). */
    static final String STASH_ARTIFACT_TYPE = "application/vnd.io.jenkins.oras-artifact-manager.stash.v1+json";

    /** Annotation storing the archived path (relative to the artifacts root) on a file referrer manifest. */
    static final String ANNOTATION_ARCHIVED_PATH = "io.jenkins.oras-artifact-manager.path";

    /** Annotation storing the Jenkins job full name on the build root manifest. */
    static final String ANNOTATION_JOB_FULL_NAME = "io.jenkins.oras-artifact-manager.job";

    /** Annotation storing the Jenkins build number on the build root manifest. */
    static final String ANNOTATION_BUILD_NUMBER = "io.jenkins.oras-artifact-manager.build-number";

    private static final Pattern INVALID_SEGMENT_CHARS = Pattern.compile("[^a-z0-9._-]+");
    private static final Pattern INVALID_TAG_CHARS = Pattern.compile("[^a-zA-Z0-9._-]+");

    private OrasNaming() {}

    /**
     * Turn a Jenkins job full name (e.g. {@code "folder/My Job"}) into an OCI compliant repository name
     * (e.g. {@code "folder/my-job"}).
     * @param jobFullName the job full name
     * @return the sanitized repository name
     */
    static String repositoryFor(String jobFullName) {
        String[] segments = jobFullName.split("/");
        return String.join(
                "/", Arrays.stream(segments).map(OrasNaming::sanitizeSegment).toArray(String[]::new));
    }

    private static String sanitizeSegment(String segment) {
        String lower = segment.toLowerCase(java.util.Locale.ROOT);
        String sanitized = INVALID_SEGMENT_CHARS.matcher(lower).replaceAll("-");
        sanitized = sanitized.replaceAll("^[.\\-_]+", "").replaceAll("[.\\-_]+$", "");
        return sanitized.isEmpty() ? "job" : sanitized;
    }

    /**
     * Build the tag used for a build's root artifact: simply the build number.
     * @param buildNumber the build number
     * @return the tag
     */
    static String buildTag(int buildNumber) {
        return String.valueOf(buildNumber);
    }

    /**
     * Build the tag used for a stash.
     * @param stashName the stash name
     * @return the tag
     */
    static String stashTag(String stashName) {
        return STASH_TAG_PREFIX + sanitizeTag(stashName);
    }

    private static String sanitizeTag(String name) {
        return INVALID_TAG_CHARS.matcher(name).replaceAll("-");
    }
}
