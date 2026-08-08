package io.jenkins.plugins.oras_artifacts;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Action;
import hudson.model.Run;
import java.util.Collection;
import java.util.Collections;
import jenkins.model.ArtifactManager;
import jenkins.model.TransientActionFactory;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Attaches an {@link OrasArtifactAction} to every build whose artifact manager is an
 * {@link OrasArtifactManager} - and only such builds. This is what makes the "show OCI reference"
 * feature opt-in: it simply doesn't exist for builds using any other artifact manager (or none).
 */
@Restricted(NoExternalUse.class)
@Extension
public class OrasArtifactActionFactory extends TransientActionFactory<Run> {

    @Override
    public Class<Run> type() {
        return Run.class;
    }

    @NonNull
    @Override
    public Collection<? extends Action> createFor(@NonNull Run run) {
        ArtifactManager artifactManager = run.getArtifactManager();
        if (artifactManager instanceof OrasArtifactManager orasArtifactManager) {
            return Collections.singleton(new OrasArtifactAction(run, orasArtifactManager));
        }
        return Collections.emptySet();
    }
}
