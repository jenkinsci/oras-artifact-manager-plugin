package io.jenkins.plugins.oras_artifacts;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.AbortException;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.BuildListener;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.remoting.VirtualChannel;
import hudson.slaves.WorkspaceList;
import hudson.util.DirScanner;
import hudson.util.io.ArchiverFactory;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serial;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import jenkins.agents.ControllerToAgentFileCallable;
import jenkins.model.ArtifactManager;
import jenkins.util.VirtualFile;
import org.jenkinsci.plugins.workflow.flow.StashManager;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link ArtifactManager} storing archived artifacts and stashes in an OCI registry through ORAS.
 *
 * <p>This is a first iteration / prototype: every registry call is performed sequentially, without
 * retries and without concurrency, favoring simplicity over throughput.
 */
@Restricted(NoExternalUse.class)
public class OrasArtifactManager extends ArtifactManager implements StashManager.StashAwareArtifactManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(OrasArtifactManager.class);

    private final OrasGenericArtifactConfig config;
    private transient Run<?, ?> build;
    private transient String repository;
    private transient String tag;

    public OrasArtifactManager(Run<?, ?> build, OrasGenericArtifactConfig config) {
        this.config = config;
        onLoad(build);
    }

    @Override
    public void onLoad(@NonNull Run<?, ?> build) {
        this.build = build;
        this.repository = config.repositoryFor(build.getParent().getFullName());
        this.tag = OrasNaming.buildTag(build.getNumber());
    }

    @Override
    public void archive(FilePath workspace, Launcher launcher, BuildListener listener, Map<String, String> artifacts)
            throws IOException, InterruptedException {
        if (artifacts.isEmpty()) {
            return;
        }
        OrasConnection connection = config.resolve();
        connection
                .createClient()
                .ensureBuildRoot(repository, tag, build.getParent().getFullName(), build.getNumber());
        LOGGER.debug("Archiving {} artifact(s) to {}:{}", artifacts.size(), repository, tag);
        workspace.act(new ArchiveFiles(connection, repository, tag, artifacts));
    }

    @Override
    public boolean delete() {
        return config.createClient().delete(repository, tag);
    }

    @Override
    public VirtualFile root() {
        return new OrasVirtualFile(config.createClient(), repository, tag, "");
    }

    @Override
    public void stash(
            @NonNull String name,
            @NonNull FilePath workspace,
            @NonNull Launcher launcher,
            @NonNull EnvVars env,
            @NonNull TaskListener listener,
            String includes,
            String excludes,
            boolean useDefaultExcludes,
            boolean allowEmpty)
            throws IOException, InterruptedException {
        FilePath tempDir = WorkspaceList.tempDir(workspace);
        if (tempDir == null) {
            throw new AbortException("Could not make temporary directory in " + workspace);
        }
        workspace.act(new Stash(
                config.resolve(),
                repository,
                name,
                includes,
                excludes,
                useDefaultExcludes,
                allowEmpty,
                tempDir.getRemote()));
        listener.getLogger().printf("Stashed '%s'%n", name);
    }

    @Override
    public void unstash(
            @NonNull String name,
            @NonNull FilePath workspace,
            @NonNull Launcher launcher,
            @NonNull EnvVars env,
            @NonNull TaskListener listener)
            throws IOException, InterruptedException {
        if (!config.createClient().hasStash(repository, name)) {
            throw new AbortException("No such saved stash '" + name + "'");
        }
        workspace.act(new Unstash(config.resolve(), repository, name));
    }

    @Override
    public void clearAllStashes(@NonNull TaskListener listener) {
        config.createClient().deleteAllStashes(repository);
        listener.getLogger().println("Deleted all stashes on the ORAS registry");
    }

    @Override
    public void copyAllArtifactsAndStashes(@NonNull Run<?, ?> to, @NonNull TaskListener listener) throws IOException {
        ArtifactManager artifactManager = to.pickArtifactManager();
        if (!(artifactManager instanceof OrasArtifactManager targetManager)) {
            throw new AbortException(
                    "Cannot copy artifacts and stashes to %s using %s".formatted(to, artifactManager.getClass()));
        }
        RegistryClient client = config.createClient();
        // Copy build artifacts
        List<RegistryClient.ArchivedFile> files = client.listArchivedFiles(repository, tag);
        if (!files.isEmpty()) {
            client.ensureBuildRoot(
                    targetManager.repository, targetManager.tag, to.getParent().getFullName(), to.getNumber());
            for (RegistryClient.ArchivedFile file : files) {
                Path tmp = Files.createTempFile("oras-artifact-manager-copy-", ".bin");
                try {
                    try (InputStream is = client.openFile(repository, tag, file.path())) {
                        Files.copy(is, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                    client.archiveFile(targetManager.repository, targetManager.tag, file.path(), tmp);
                } finally {
                    Files.deleteIfExists(tmp);
                }
            }
            listener.getLogger().printf("Copied %d artifact(s) to %s%n", files.size(), to);
        }
        // Copy stashes: not tracked explicitly here since there's no index of stash names besides
        // registry tags; a best-effort discovery via tag listing is done in RegistryClient if needed
        // by callers. For this prototype, stashes are intentionally left to the caller to re-stash.
    }

    /**
     * Master to slave callable that uploads archived files to the ORAS registry.
     */
    private record ArchiveFiles(OrasConnection connection, String repository, String tag, Map<String, String> artifacts)
            implements ControllerToAgentFileCallable<Void> {

        @Serial
        private static final long serialVersionUID = 1L;

        @Override
        public Void invoke(File workspace, VirtualChannel channel) throws IOException {
            RegistryClient client = connection.createClient();
            for (Map.Entry<String, String> entry : artifacts.entrySet()) {
                String archivedPath = entry.getKey();
                Path source = new File(workspace, entry.getValue()).toPath();
                client.archiveFile(repository, tag, archivedPath, source);
            }
            return null;
        }
    }

    /**
     * Master to slave callable that archives and uploads a stash to the ORAS registry.
     */
    private record Stash(
            OrasConnection connection,
            String repository,
            String name,
            String includes,
            String excludes,
            boolean useDefaultExcludes,
            boolean allowEmpty,
            String tempDir)
            implements ControllerToAgentFileCallable<Void>, Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        @Override
        public Void invoke(File f, VirtualChannel channel) throws IOException, InterruptedException {
            java.nio.file.Path tempDirPath = java.nio.file.Paths.get(tempDir);
            Files.createDirectories(tempDirPath);
            Path tmp = Files.createTempFile(tempDirPath, "stash", ".tgz");
            try {
                int count;
                try (OutputStream os = Files.newOutputStream(tmp)) {
                    count = new FilePath(f)
                            .archive(
                                    ArchiverFactory.TARGZ,
                                    os,
                                    new DirScanner.Glob(
                                            Util.fixEmpty(includes) == null ? "**" : includes,
                                            excludes,
                                            useDefaultExcludes));
                }
                if (count == 0 && !allowEmpty) {
                    throw new AbortException("No files included in stash");
                }
                connection.createClient().pushStash(repository, name, tmp);
            } finally {
                Files.deleteIfExists(tmp);
            }
            return null;
        }
    }

    /**
     * Master to slave callable that downloads and unpacks a stash from the ORAS registry.
     */
    private record Unstash(OrasConnection connection, String repository, String name)
            implements ControllerToAgentFileCallable<Void> {

        @Serial
        private static final long serialVersionUID = 1L;

        @Override
        public Void invoke(File f, VirtualChannel channel) throws IOException, InterruptedException {
            Path tmp = Files.createTempFile("oras-artifact-manager-unstash-", ".tgz");
            try {
                connection.createClient().pullStash(repository, name, tmp);
                try (InputStream is = Files.newInputStream(tmp)) {
                    new FilePath(f).untarFrom(is, FilePath.TarCompression.GZIP);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
            return null;
        }
    }
}
