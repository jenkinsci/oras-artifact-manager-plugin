package io.jenkins.plugins.oras_artifacts;

import hudson.Extension;
import hudson.model.Job;
import hudson.model.PageDecorator;
import hudson.model.Run;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.Ancestor;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerRequest2;

/**
 * Injects a small script (via {@code header.jelly}) that decorates each archived file listed in an
 * artifact list with its OCI reference, but only when that artifact list is for a build whose
 * artifact manager is actually {@link OrasArtifactManager}.
 */
@Restricted(NoExternalUse.class)
@Extension
public class OrasArtifactManagerPageDecorator extends PageDecorator {

    public boolean isEnabled() {
        StaplerRequest2 request = Stapler.getCurrentRequest2();
        if (request == null) {
            return false;
        }

        // Build page
        Ancestor runAncestor = request.findAncestor(Run.class);
        if (runAncestor != null && runAncestor.getObject() instanceof Run<?, ?> run) {
            return run.getArtifactManager() instanceof OrasArtifactManager;
        }

        // The job overview page
        Ancestor jobAncestor = request.findAncestor(Job.class);
        if (jobAncestor != null && jobAncestor.getObject() instanceof Job<?, ?> job) {
            Run<?, ?> lastSuccessful = job.getLastSuccessfulBuild();
            return lastSuccessful != null && lastSuccessful.getArtifactManager() instanceof OrasArtifactManager;
        }

        return false;
    }
}
