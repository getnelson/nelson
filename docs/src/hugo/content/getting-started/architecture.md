---
layout: "single"
title: Architecture
preamble: >
  Nelson is unlike many systems presently available in the market, and its architecture is discretely composable, and indeed, composability is an explicit design goal of the Nelson team. In practice this means that - unlikle other monolithic systems - Nelson lets you bring your own CI, bring your own scheudler, bring your own credential management and so on.
menu:
  main:
    parent: gs
    identifier: gs-arch
    url: /getting-started/architecture.html
    weight: 2
---

With any new system, it is often useful to get a sense for how data is moving around the system, and the trade offs a given system is making. The following sections cover a few of these design choices.

# Data Flow

Nelson splits the build->deploy->release phases into two major parts, each with distinctly different properties. First, the building of the containers and publishing to a staging registry. Second, the deployment, validation, and migration of traffic. The following diagram illustrates a high-level view of the first major part of the workflow.

<div class="clearing">
  <img src="/img/high-level-workflow.png" />
  <small><em>Workflow overview</em></small>
</div>

There are a range of automated steps at every phase of the build and release pipeline; only the most minimal metadata is needed in the resulting Github Release to conduct a deployment. Nelson is agnostic to what language your application is built with or how it is tooled.You are free to integrate however you prefer, but we provide some tools to make doing so easy. For more information on CI integration, please see the [section on Deployables](/getting-started/deployables.html).

# Domain Redundancy

To run Nelson you will need access to a target "datacenter". One can consider this logical datacenter - in practice - to be a [failure domain](https://en.wikipedia.org/wiki/Failure_domain). Every system should be redundant within the domain, but isolated from other datacenters. Typically Nelson requires the datacenter to be setup with a few key services before it can effectively be used. Imagine the setup with the following components:

<div class="clearing">
  <img src="/img/atomic-datacenter.png" />
  <small><em>Failure domain</em></small>
</div>

Logically, Nelson typically sits outside one of its target datacenters. This could mean it lives at your office next to your Github, or it might actually reside in one of the target datacenters itself. This is an operational choice that you would make, and provided Nelson has network line of sight to these key services, you can put it wherever you like. With that being said, it is recommended that your stage and runtime docker registries be different, as the performance characteristics of the runtime and staging registries are quite different. Specifically, the runtime registry receives many requests to `pull` images whilst deploying containers to the cluster, whilst the staging registry largely has mass-writes from your build system. Keeping these separate ensures that either side of that interaction does not fail the other.

Whatever you choose, the rest of this operators guide will assume that you have configured one or more logical datacenters. If you don't want to setup a *real* datacenter, [see Timothy Perrett's Github](https://github.com/timperrett/hashpi) to learn how to setup a [Raspberry PI](https://www.raspberrypi.org/) cluster which can serve as your "datacenter" target.

# Availability

Whilst many systems authors today choose to adopt the complexity of high-availability, clustered systems, this is often done for the wrong reasons. Nelson is - by design - not high-availability, and avoids being in the "hot path" for runtime execution. This means that Nelson is operationally simpler than many of its competing systems, and with the correct supervision and a scale up approach, Nelson can reach way into the thousands of deployments a day range, without breaking a sweat.

# Next Steps

* Learn about how Nelson allows seamless integration with any CI system [using Deployables](/getting-started/deployables.html).
* Learn how Nelson enables operators to focus on enabling the rest of the organization via [Nelson's blueprints subsystem](/getting-started/blueprints.html).