package io.jenkins.plugins.oras_artifacts;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Run;
import jenkins.model.ArtifactManager;
import jenkins.model.ArtifactManagerFactory;
import jenkins.model.ArtifactManagerFactoryDescriptor;
import org.jenkinsci.Symbol;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * Factory instantiating an {@link OrasArtifactManager} for every build, once configured globally.
 */
@Restricted(NoExternalUse.class)
public class OrasArtifactManagerFactory extends ArtifactManagerFactory {

    private final OrasGenericArtifactConfig config;

    @DataBoundConstructor
    public OrasArtifactManagerFactory(OrasGenericArtifactConfig config) {
        if (config == null) {
            throw new IllegalArgumentException();
        }
        this.config = config;
    }

    public OrasGenericArtifactConfig getConfig() {
        return config;
    }

    @CheckForNull
    @Override
    public ArtifactManager managerFor(Run<?, ?> build) {
        return new OrasArtifactManager(build, config);
    }

    @Extension
    @Symbol("oras")
    public static final class DescriptorImpl extends ArtifactManagerFactoryDescriptor {
        @NonNull
        @Override
        public String getDisplayName() {
            return "ORAS Registry Artifact Storage";
        }
    }
}
