package io.jenkins.plugins.oras_artifacts;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.remoting.Callable;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serial;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import jenkins.util.VirtualFile;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * A {@link VirtualFile} view over the files archived for a single build, backed by the OCI
 * referrers of the build's root artifact.
 */
@Restricted(NoExternalUse.class)
final class OrasVirtualFile extends VirtualFile {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String repository;
    private final String tag;
    private final String path;

    private final transient RegistryClient client;

    OrasVirtualFile(RegistryClient client, String repository, String tag, String path) {
        this.client = client;
        this.repository = repository;
        this.tag = tag;
        this.path = path;
    }

    @NonNull
    @Override
    public String getName() {
        if (path.isEmpty()) {
            return "";
        }
        int idx = path.lastIndexOf('/');
        return idx >= 0 ? path.substring(idx + 1) : path;
    }

    @NonNull
    @Override
    public URI toURI() {
        try {
            return new URI("oras", repository + ":" + tag, "/" + path, null);
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    @CheckForNull
    @Override
    public URL toExternalURL() {
        return null;
    }

    @Override
    public VirtualFile getParent() {
        int idx = path.lastIndexOf('/');
        String parentPath = idx >= 0 ? path.substring(0, idx) : "";
        return new OrasVirtualFile(client, repository, tag, parentPath);
    }

    @Override
    public boolean isDirectory() {
        if (path.isEmpty()) {
            return true;
        }
        String prefix = path + "/";
        return client.listArchivedFiles(repository, tag).stream()
                .anyMatch(f -> f.path().startsWith(prefix));
    }

    @Override
    public boolean isFile() {
        return findFile().isPresent();
    }

    @Override
    public boolean exists() {
        return path.isEmpty() || isDirectory() || isFile();
    }

    @NonNull
    @Override
    public VirtualFile[] list() {
        String prefix = path.isEmpty() ? "" : path + "/";
        Set<String> children = new LinkedHashSet<>();
        for (RegistryClient.ArchivedFile file : client.listArchivedFiles(repository, tag)) {
            if (!file.path().startsWith(prefix)) {
                continue;
            }
            String remainder = file.path().substring(prefix.length());
            int idx = remainder.indexOf('/');
            String child = idx >= 0 ? remainder.substring(0, idx) : remainder;
            if (!child.isEmpty()) {
                children.add(prefix + child);
            }
        }
        List<VirtualFile> result = new ArrayList<>();
        for (String childPath : children) {
            result.add(new OrasVirtualFile(client, repository, tag, childPath));
        }
        return result.toArray(new VirtualFile[0]);
    }

    @NonNull
    @Override
    public VirtualFile child(@NonNull String name) {
        String childPath = path.isEmpty() ? name : path + "/" + name;
        return new OrasVirtualFile(client, repository, tag, childPath);
    }

    @Override
    public long length() {
        return findFile().map(f -> f.layer().getSize()).orElse(0L);
    }

    @Override
    public long lastModified() {
        return 0L;
    }

    @Override
    public boolean canRead() {
        return true;
    }

    @Override
    public InputStream open() throws IOException {
        RegistryClient.ArchivedFile file =
                findFile().orElseThrow(() -> new FileNotFoundException("No such archived file: " + path));
        return client.openFile(repository, tag, file.path());
    }

    @Override
    public <V> V run(Callable<V, IOException> callable) throws IOException {
        return callable.call();
    }

    private Optional<RegistryClient.ArchivedFile> findFile() {
        if (path.isEmpty()) {
            return Optional.empty();
        }
        return client.listArchivedFiles(repository, tag).stream()
                .filter(f -> f.path().equals(path))
                .findFirst();
    }
}
