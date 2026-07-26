# oras-artifact-manager

## Introduction

Stores archived build artifacts and stashes in an OCI registry using
[ORAS](https://oras.land/), via the [ORAS Java API plugin](https://github.com/jenkinsci/oras-java-api-plugin).

This is a first-iteration prototype. Every registry call is performed sequentially, without retries
and without concurrency, favoring a simple, easy-to-follow implementation over throughput.

### Artifact model

- The OCI **repository** is the sanitized Jenkins job full name (folders included), e.g. the job
  `folder/My Job` maps to the repository `folder/my-job`.
- Every build's archived artifacts are anchored by a small "root" artifact, tagged with the build
  number (e.g. `9`).
  This root artifact carries no content of its own; it only exists to be referenced.
- Every archived file is pushed as its own single-layer manifest that refers back to the build's
  root artifact using the OCI 1.1 `subject` field (i.e. it's a
  [referrer](https://github.com/opencontainers/distribution-spec/blob/main/spec.md#listing-referrers)
  of the root artifact). The archived path (relative to the artifacts root) is stored both as a
  manifest annotation and as the layer's `org.opencontainers.image.title` annotation.
- Discovering every file archived for a build is therefore a single call to the registry's
  referrers API for the root artifact's digest - no separate index needs to be built or maintained.
- Stashes are simpler: a single artifact, tagged `stash-<name>`, with one tar.gz layer.

## Getting started

Configure a global ORAS registry (**Manage Jenkins » System**, under the *Build artifact management
for builds* / *Artifact Manager* section), pointing it at your registry URL and, optionally,
credentials. Then add an *ORAS Registry Artifact Storage* artifact manager, referencing that
registry configuration.

Archived artifacts and stashes from every job will then be stored in the configured registry,
following the model described above.

## Issues

Report issues and enhancements in the [GitHub issue tracker](https://github.com/jenkinsci/oras-artifact-manager-plugin/issues).

## Contributing

Refer to our [contribution guidelines](https://github.com/jenkinsci/.github/blob/master/CONTRIBUTING.md)

## LICENSE

Licensed under MIT, see [LICENSE](LICENSE.md)


