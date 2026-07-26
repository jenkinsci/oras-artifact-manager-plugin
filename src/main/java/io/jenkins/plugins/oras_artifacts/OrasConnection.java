package io.jenkins.plugins.oras_artifacts;

import java.io.Serial;
import java.io.Serializable;
import land.oras.Registry;

/**
 * A self-contained, serializable descriptor of how to connect to the ORAS registry
 *
 * @param registryUrl the registry URL,
 * @param insecure whether to use plain HTTP and skip TLS verification
 * @param username the registry username or null
 * @param password the registry password or null
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
