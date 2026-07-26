package io.jenkins.plugins.oras_artifacts;

import java.io.Serial;
import java.io.Serializable;
import land.oras.Registry;

/**
 * A self-contained, serializable descriptor of how to connect to the ORAS registry, resolved from
 * {@link OrasGenericArtifactConfig} on the Jenkins controller (where credentials lookup is
 * available) so that it can be shipped to a build agent over remoting.
 *
 * @param registryUrl the registry URL, e.g. {@code "registry.example.com"} or {@code "localhost:5000"}
 * @param insecure whether to use plain HTTP and skip TLS verification
 * @param username the registry username, or {@code null} for anonymous / host-based auth
 * @param password the registry password, or {@code null} for anonymous / host-based auth
 */
record OrasConnection(String registryUrl, boolean insecure, String username, String password) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    RegistryClient createClient() {
        Registry.Builder builder = Registry.Builder.builder();
        if (username != null) {
            builder = builder.defaults(username, password);
            if (insecure) {
                builder = builder.withInsecure(true).withSkipTlsVerify(true);
            }
        } else {
            builder = builder.insecure();
        }
        builder = builder.withRegistry(registryUrl);
        return new RegistryClient(builder.build());
    }
}
