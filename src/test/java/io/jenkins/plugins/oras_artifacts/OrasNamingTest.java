package io.jenkins.plugins.oras_artifacts;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Tests the sanitization logic turning Jenkins job/build/stash names into OCI-spec compliant
 * repository/tag names - this is the crux of how this plugin models artifacts, so it deserves
 * dedicated coverage beyond what's implicitly exercised by the other tests.
 */
class OrasNamingTest {

    @Test
    void repositoryForLowercasesAndSanitizesEachSegment() {
        assertEquals("folder/my-job", OrasNaming.repositoryFor("folder/My Job"));
        assertEquals("a/b/c", OrasNaming.repositoryFor("A/B/C"));
    }

    @Test
    void repositoryForReplacesInvalidCharacters() {
        assertEquals("job-name-with-stuff", OrasNaming.repositoryFor("job name #with$stuff"));
    }

    @Test
    void repositoryForNeverProducesEmptySegments() {
        assertEquals("job/job", OrasNaming.repositoryFor("!!!/###"));
    }

    @Test
    void buildTagIsJustTheBuildNumber() {
        assertEquals("42", OrasNaming.buildTag(42));
    }

    @Test
    void stashTagIsSanitizedAndPrefixed() {
        assertEquals("stash-my-stash", OrasNaming.stashTag("my-stash"));
        assertEquals("stash-my-stash-2", OrasNaming.stashTag("my stash 2"));
    }
}
