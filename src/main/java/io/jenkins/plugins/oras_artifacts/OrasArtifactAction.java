package io.jenkins.plugins.oras_artifacts;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Action;
import hudson.model.Run;
import java.io.IOException;
import java.util.Optional;
import net.sf.json.JSONObject;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.verb.GET;

/**
 * A small, per-build action exposing the OCI reference of each file archived through
 * {@link OrasArtifactManager}
 */
@Restricted(NoExternalUse.class)
public class OrasArtifactAction implements Action {

    /** The URL segment this action is reachable under, relative to the build's own URL. */
    static final String URL_NAME = "oras-artifact-manager";

    private final Run<?, ?> run;
    private final OrasArtifactManager manager;

    OrasArtifactAction(Run<?, ?> run, OrasArtifactManager manager) {
        this.run = run;
        this.manager = manager;
    }

    @CheckForNull
    @Override
    public String getIconFileName() {
        return null;
    }

    @CheckForNull
    @Override
    public String getDisplayName() {
        return null;
    }

    @CheckForNull
    @Override
    public String getUrlName() {
        return URL_NAME;
    }

    /**
     * Return the OCI reference of the archived file at the given path, as JSON, or a 404 if no
     * such file was archived (or the caller lacks permission to see it).
     */
    @GET
    public void doReference(StaplerRequest2 req, StaplerResponse2 rsp, @QueryParameter String path) throws IOException {
        run.checkPermission(Run.ARTIFACTS);
        if (path == null || path.isBlank()) {
            rsp.sendError(400, "Missing 'path' parameter");
            return;
        }
        RegistryClient client = manager.getConfig().createClient();
        Optional<RegistryClient.ArchivedFile> file =
                client.findArchivedFile(manager.getRepository(), manager.getTag(), path);
        if (file.isEmpty()) {
            rsp.sendError(404, "No such archived file: " + path);
            return;
        }
        OrasArtifactReference reference = OrasArtifactReference.of(
                manager.getConfig().getRegistryUrl(), manager.getRepository(), manager.getTag(), file.get());
        JSONObject json = reference.toJson();
        rsp.setContentType("application/json;charset=UTF-8");
        rsp.getWriter().write(json.toString());
    }

    @NonNull
    Run<?, ?> getRun() {
        return run;
    }
}
