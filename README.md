# ORAS Artifact Manager Jenkins Plugin


> [!WARNING]
> The ORAS Java SDK is currently in **beta** state and might impact the stability of this plugin.
>
> It's configuration and APIs might change in future releases

<p align="left">
<a href="https://oras.land/"><img src="https://oras.land/img/oras.svg" alt="banner" width="200px"></a>
</p>

## Introduction

Stores archived build artifacts and stashes in an OCI registry using
[ORAS](https://oras.land/), via the [ORAS Java API plugin](https://github.com/jenkinsci/oras-java-api-plugin).

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


Example of an Artifact (discovered via ORAS)

```shell
oras discover localhost:5000/test:13
localhost:5000/test@sha256:ea376d3b8be4af2ddef42516fafe57e27e1d667f50b8c6b45c57807aa189c93c
└── application/vnd.io.jenkins.oras-artifact-manager.file.v1+json
    ├── sha256:4ab3a792e7052bb85cd2d75c8a5ae4910fac665bf053f4bb6e5c10c9d2527733
    │   └── [annotations]
    │       ├── io.jenkins.oras-artifact-manager.path: fofo.txt
    │       └── org.opencontainers.image.created: "2026-07-26T10:10:34Z"
    ├── sha256:f160aba27541d93f989602ba74315d6c768a338271c99855a11d726a0572e5aa
    │   └── [annotations]
    │       ├── org.opencontainers.image.created: "2026-07-26T10:10:34Z"
    │       └── io.jenkins.oras-artifact-manager.path: test/bar.txt
    └── sha256:5b6950cad951815434d9cb49ac640c2e09f6a34fe54a746daad07cb959944975
        └── [annotations]
            ├── io.jenkins.oras-artifact-manager.path: test/tata/toto.txt
            └── org.opencontainers.image.created: "2026-07-26T10:10:34Z"
```

The root manifest looks like

```shell
oras manifest fetch localhost:5000/test:13 | jq
{
  "schemaVersion": 2,
  "mediaType": "application/vnd.oci.image.manifest.v1+json",
  "artifactType": "application/vnd.io.jenkins.oras-artifact-manager.build.v1+json",
  "config": {
    "mediaType": "application/vnd.oci.empty.v1+json",
    "digest": "sha256:44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a",
    "size": 2,
    "data": "e30="
  },
  "annotations": {
    "org.opencontainers.image.created": "2026-07-26T10:10:34Z",
    "io.jenkins.oras-artifact-manager.build-number": "13",
    "io.jenkins.oras-artifact-manager.job": "test"
  },
  "layers": []
}
```

A file manifest looks like

```shell
oras manifest fetch localhost:5000/test@sha256:4ab3a792e7052bb85cd2d75c8a5ae4910fac665bf053f4bb6e5c10c9d2527733 | jq
{
  "schemaVersion": 2,
  "mediaType": "application/vnd.oci.image.manifest.v1+json",
  "artifactType": "application/vnd.io.jenkins.oras-artifact-manager.file.v1+json",
  "config": {
    "mediaType": "application/vnd.oci.empty.v1+json",
    "digest": "sha256:44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a",
    "size": 2,
    "data": "e30="
  },
  "subject": {
    "mediaType": "application/vnd.oci.image.manifest.v1+json",
    "digest": "sha256:ea376d3b8be4af2ddef42516fafe57e27e1d667f50b8c6b45c57807aa189c93c",
    "size": 506
  },
  "annotations": {
    "io.jenkins.oras-artifact-manager.path": "fofo.txt",
    "org.opencontainers.image.created": "2026-07-26T10:10:34Z"
  },
  "layers": [
    {
      "mediaType": "application/vnd.oci.image.layer.v1.tar",
      "digest": "sha256:ce88c3c4e094598f8254b0517c6efa781adccdda66d796fd5100f7f10947447d",
      "size": 5,
      "annotations": {
        "org.opencontainers.image.title": "fofo.txt"
      }
    }
  ]
}
```

## Getting started

Configure a global ORAS registry (**Manage Jenkins » System**, under the *Build artifact management
for builds* / *Artifact Manager* section), pointing it at your registry URL and, optionally,
credentials. Then add an *ORAS Registry Artifact Storage* artifact manager, referencing that
registry configuration.

Archived artifacts and stashes from every job will then be stored in the configured registry,
following the model described above.

## Issues

Report issues and enhancements in the [GitHub issue tracker](https://github.com/jenkinsci/oras-artifact-manager-plugin/issues).

## LICENSE

Licensed under MIT, see [LICENSE](LICENSE.md)

