---
layout: "single"
title: Deployables
preamble: >
  An important differentiator of Nelson is that it does not care what kind of CI system you use. Nelson avoids this coupling directly to a CI platform by signaling the addition of deployments, via [GitHub Deployments](https://developer.github.com/v3/repos/deployments/). This deployment payload serves two purposes: to ensure versioning so that the code and deployment history can be traced through GitHub, and to inform Nelson which units to deploy for a given deployment payload. By comparison, the manifest located in the user’s repository enumerates all the units and plans under said repository's purview - the logical definition - the deployables encoded in a deployment event tell Nelson which units to actually deploy, and the concrete artifacts to use.

menu:
  main:
    parent: gs
    identifier: gs-deployables
    url: /getting-started/deployables.html
    weight: 4
---

Manifests describe a logical definition (e.g. a unit name) and deployables tie that logical definition to a physical one (e.g. a specific Docker image). This prevents the need to update the manifest every time a version is bumped (frequent in a continuous delivery setting), whilst avoiding the need for a mutable tag like `latest`. The [Slipway](https://github.com/getnelson/slipway) tool can help with both creating a deployable file as well as triggering the actual GitHub Deployment. Slipway is available as a [small statically linked binary](https://github.com/getnelson/slipway/releases), ready to be pulled and used in a CI pipeline.

## Protocol

The deployable format - what we call NLDP - is a small binary protocol based on [Google Protocol Buffers](https://developers.google.com/protocol-buffers/). This protocol is located within the Nelson code base, but is automatically extracted into [getnelson/api](https://github.com/getnelson/api) for ease of client code generation. Consider the following protocol definition:

```
message Deployable {
  string unit_name = 1;
  oneof version {
    SemanticVersion semver = 2;
  }
  oneof kind {
    Container container = 3;
  }
}
```

The `unit_name` and `version` is what Nelson enters into its database - therefore the unit name must match a unit name in the repository manifest. Because Nelson is version-aware, the same logical version cannot be deployed twice - the version must be incremented, though not necessarily sequentially, with each new release. At present, Nelson only supports [semantic versioning](https://semver.org/), with scope for supporting other sortable schemes in the future.

The Docker image tag typically matches the semantic version, but it doesn't have to. Note that the provided Docker image is assumed to exist by the time the release (and therefore the deployment) is made, likely built and pushed by an earlier step in the CI pipeline. Nelson does little with the image string other than forward it to the backing scheduler.
