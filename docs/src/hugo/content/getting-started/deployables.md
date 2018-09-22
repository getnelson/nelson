---
layout: "single"
title: Deployables
preamble: >
  An important differention point of Nelson, is that is does not care what kind of CI system you use. Nelson avoids this coupling directly to a CI platform by signaling the addition of deployments, via [Github releases](https://help.github.com/articles/about-releases/). This release serves two purposes: to ensure versioning so that the code and deployment history can be traced through GitHub, and to inform Nelson which units to deploy for that release. Where the manifest enumerates all the units and plans under the repository's purview - the logical defintion - the deployables attached to a release tell Nelson which units to actually deploy for that release, and the concrete artifacts to use.

#contents:
#- Command Line
#- Server
menu:
  main:
    parent: gs
    identifier: gs-deployables
    url: /getting-started/deployables.html
    weight: 4
---

Manifests describe a logical definition (e.g. a unit name) and deployables tie that logical definition to a physical one (e.g. a specific Docker image). This prevents the need to update the manifest everytime a version is bumped (frequent in a continuous delivery setting), whilst avoiding the need for a mutable tag like `latest`.

The [Slipway](https://github.com/getnelson/slipway) tool can help with both creating a deployable file as well as making the actual annotated release. It is available as a [small statically linked binary](https://github.com/getnelson/slipway/releases) ready to be pulled and used in a CI pipeline.

If you'd like, the deployable file format is pretty simple and can be created without Slipway. Each file should be named as `<unit name>.deployable.yml` and look like:

```yaml
---
name: <unit name>
version: <major>.<minor>.<patch>
output:
  kind: docker
  image: <docker image>
```

The unit name and version in the file name and contents is what Nelson enters into its database - therefore the unit name in the filename should match the unit name in the contents which should match a unit name in the manifest. Because Nelson is version-aware, the same logical version cannot be deployed twice - the version must be incremented, though not necessarily sequential, with each new release.

The Docker image tag typically matches the version, but it doesn't have to. Note that the provided Docker image is assumed to exist by the time the release (and therefore the deployment) is made, likely built and pushed by an earlier step in the CI pipeline. Nelson does little with the image string other than forward it to the backing scheduler.

As an example, consider a manifest that contains `foo`, `bar`, and `baz` as units. To tell Nelson to deploy `foo` and `bar` for a release, something similar to the following would be attached to the release (likely with Slipway):

In `foo.deployable.yml`:
```yaml
---
name: foo
version: 1.2.3
output:
  kind: docker
  image: your.docker.com/repo/foo:1.2.0
```

In `bar.deployable.yml`:
```yaml
---
name: bar
version: 2.1.3
output:
  kind: docker
  image: your.docker.com/repo/bar:2.1.3
```