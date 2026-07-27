package io.jenkins.plugins.oras_artifacts;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;

import io.jenkins.plugins.casc.misc.ConfiguredWithCode;
import io.jenkins.plugins.casc.misc.JenkinsConfiguredWithCodeRule;
import io.jenkins.plugins.casc.misc.junit.jupiter.WithJenkinsConfiguredWithCode;
import java.util.List;
import jenkins.model.ArtifactManagerConfiguration;
import jenkins.model.ArtifactManagerFactory;
import org.junit.jupiter.api.Test;

/**
 * Tests support for Configuration as Code (JCasC), following the same pattern used by
 * {@code jobcacher-oras-storage-plugin} and {@code artifactory-artifact-manager-plugin}.
 */
@WithJenkinsConfiguredWithCode
class ConfigurationAsCodeTest {

    @Test
    @ConfiguredWithCode("configuration-as-code.yml")
    void shouldSupportConfigurationAsCode(JenkinsConfiguredWithCodeRule jenkinsRule) {
        OrasGenericArtifactConfig config = configOfSoleFactory();
        assertThat(config.getRegistryUrl(), is("localhost:5000"));
        assertThat(config.getCredentialsId(), is("the-credentials-id"));
        assertThat(config.getPrefix(), is("jenkins"));
        assertThat(config.isInsecure(), is(true));
    }

    @Test
    @ConfiguredWithCode("configuration-as-code-minimal.yml")
    void shouldSupportMinimalConfigurationAsCode(JenkinsConfiguredWithCodeRule jenkinsRule) {
        OrasGenericArtifactConfig config = configOfSoleFactory();
        assertThat(config.getRegistryUrl(), is("registry.example.com"));
        assertThat(config.getCredentialsId(), nullValue());
        assertThat(config.getPrefix(), nullValue());
        assertThat(config.isInsecure(), is(false));
    }

    private static OrasGenericArtifactConfig configOfSoleFactory() {
        List<ArtifactManagerFactory> factories =
                ArtifactManagerConfiguration.get().getArtifactManagerFactories();
        assertThat(factories, hasSize(1));
        ArtifactManagerFactory factory = factories.get(0);
        assertThat(factory, instanceOf(OrasArtifactManagerFactory.class));
        return ((OrasArtifactManagerFactory) factory).getConfig();
    }
}
