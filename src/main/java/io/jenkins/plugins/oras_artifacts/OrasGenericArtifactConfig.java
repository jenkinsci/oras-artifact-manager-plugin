package io.jenkins.plugins.oras_artifacts;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.model.Describable;
import hudson.model.Descriptor;
import hudson.model.Item;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import java.io.IOException;
import java.io.Serializable;
import java.util.Collections;
import jenkins.model.Jenkins;
import land.oras.ContainerRef;
import land.oras.exception.OrasException;
import net.sf.json.JSONObject;
import org.apache.commons.lang3.StringUtils;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.interceptor.RequirePOST;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Global configuration of the ORAS registry used to store archived artifacts and stashes.
 */
@Extension
public class OrasGenericArtifactConfig implements Describable<OrasGenericArtifactConfig>, Serializable {

    private static final long serialVersionUID = 1L;

    private static final Logger LOGGER = LoggerFactory.getLogger(OrasGenericArtifactConfig.class);

    private String registryUrl;
    private String credentialsId;
    private String prefix;
    private boolean insecure;

    @DataBoundConstructor
    public OrasGenericArtifactConfig() {}

    public OrasGenericArtifactConfig(String registryUrl, String credentialsId, String prefix, boolean insecure) {
        this.registryUrl = registryUrl;
        this.credentialsId = credentialsId;
        this.prefix = prefix;
        this.insecure = insecure;
    }

    public String getRegistryUrl() {
        return registryUrl;
    }

    @DataBoundSetter
    public void setRegistryUrl(String registryUrl) {
        this.registryUrl = registryUrl;
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    @DataBoundSetter
    public void setCredentialsId(String credentialsId) {
        this.credentialsId = credentialsId;
    }

    /**
     * Optional prefix (namespace) prepended to every repository name derived from a job full name.
     */
    public String getPrefix() {
        return prefix;
    }

    @DataBoundSetter
    public void setPrefix(String prefix) {
        this.prefix = prefix;
    }

    public boolean isInsecure() {
        return insecure;
    }

    @DataBoundSetter
    public void setInsecure(boolean insecure) {
        this.insecure = insecure;
    }

    public static OrasGenericArtifactConfig get() {
        return ExtensionList.lookupSingleton(OrasGenericArtifactConfig.class);
    }

    /**
     * Build the OCI repository name for the given Jenkins job full name, honoring the configured prefix.
     */
    public String repositoryFor(String jobFullName) {
        String repo = OrasNaming.repositoryFor(jobFullName);
        if (StringUtils.isNotBlank(prefix)) {
            return stripTrailingSlash(prefix) + "/" + repo;
        }
        return repo;
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    /**
     * Build a new {@link RegistryClient} from this configuration. Must be called on the Jenkins
     * controller since it looks up credentials from the {@link CredentialsProvider} store.
     */
    public RegistryClient createClient() {
        return resolve().createClient();
    }

    /**
     * Resolve the credentials referenced by {@link #getCredentialsId()} into a self-contained,
     * serializable connection descriptor that can be sent to an agent over remoting (unlike this
     * class, which relies on controller-only APIs such as {@link CredentialsProvider}).
     */
    public OrasConnection resolve() {
        StandardUsernamePasswordCredentials credentials = getCredentials(credentialsId);
        String username = credentials != null ? credentials.getUsername() : null;
        String password = credentials != null ? credentials.getPassword().getPlainText() : null;
        return new OrasConnection(registryUrl, insecure, username, password);
    }

    public static StandardUsernamePasswordCredentials getCredentials(String credentialsId) {
        if (StringUtils.isBlank(credentialsId)) {
            return null;
        }
        return CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentialsInItemGroup(
                        StandardUsernamePasswordCredentials.class, Jenkins.get(), ACL.SYSTEM2, Collections.emptyList()),
                CredentialsMatchers.allOf(
                        CredentialsMatchers.withId(credentialsId),
                        CredentialsMatchers.instanceOf(StandardUsernamePasswordCredentials.class)));
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<OrasGenericArtifactConfig> {

        public DescriptorImpl() {
            load();
        }

        @NonNull
        @Override
        public String getDisplayName() {
            return "ORAS Registry";
        }

        @Override
        public boolean configure(StaplerRequest2 req, JSONObject json) throws FormException {
            save();
            return super.configure(req, json);
        }

        @SuppressWarnings("lgtm[jenkins/csrf]")
        public ListBoxModel doFillCredentialsIdItems(@AncestorInPath Item item) {
            StandardListBoxModel result = new StandardListBoxModel();
            if (item == null) {
                if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                    return result.includeCurrentValue(get().getCredentialsId());
                }
            } else {
                if (!item.hasPermission(Item.EXTENDED_READ) && !item.hasPermission(CredentialsProvider.USE_ITEM)) {
                    return result.includeCurrentValue(get().getCredentialsId());
                }
            }
            return result.includeEmptyValue()
                    .includeMatchingAs(
                            ACL.SYSTEM2,
                            item,
                            StandardUsernameCredentials.class,
                            Collections.emptyList(),
                            CredentialsMatchers.instanceOf(StandardUsernameCredentials.class))
                    .includeCurrentValue(get().getCredentialsId());
        }

        @SuppressWarnings("lgtm[jenkins/csrf]")
        public FormValidation doCheckRegistryUrl(@QueryParameter String registryUrl) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            if (StringUtils.isBlank(registryUrl)) {
                return FormValidation.error("Registry url cannot be blank");
            }
            try {
                ContainerRef.parse("%s/library/test:latest".formatted(registryUrl));
            } catch (OrasException | IllegalArgumentException e) {
                return FormValidation.error("Registry url doesn't seem valid.");
            }
            return FormValidation.ok();
        }

        @SuppressWarnings("lgtm[jenkins/csrf]")
        public FormValidation doCheckPrefix(@QueryParameter String prefix) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            if (StringUtils.isNotBlank(prefix) && prefix.endsWith("/")) {
                return FormValidation.error("Prefix must not end with a slash.");
            }
            return FormValidation.ok();
        }

        @RequirePOST
        public FormValidation doValidateOrasConfig(
                @QueryParameter("registryUrl") final String registryUrl,
                @QueryParameter("credentialsId") final String credentialsId,
                @QueryParameter("prefix") final String prefix,
                @QueryParameter("insecure") final boolean insecure) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            if (StringUtils.isBlank(registryUrl)) {
                return FormValidation.error("Registry url is required");
            }
            try {
                OrasGenericArtifactConfig config =
                        new OrasGenericArtifactConfig(registryUrl, credentialsId, prefix, insecure);
                RegistryClient client = config.createClient();
                client.testConnection(config.repositoryFor("oras-artifact-manager-connection-test"));
                LOGGER.debug("ORAS registry configuration validated");
            } catch (IOException | RuntimeException e) {
                LOGGER.error("Unable to connect to the ORAS registry", e);
                return FormValidation.error("Unable to connect to the ORAS registry: " + e.getMessage());
            }
            return FormValidation.ok("Success");
        }
    }
}
