---
layout: "single"
toc: "false"
title: "Use Cases"
name: "Use Cases"
menu:
  main:
    parent: intro
    identifier: intro-use-case
    name: Use Cases
    url: /introduction/use-case.html
    weight: 2
---

# Use Cases

Firstly, they are quite different tools - its fair to say that in some sense they are achieving similar end goals, but the manner in which they do that is very different. From the high-level, you can consider Nelson a highly-composable automator of organizational best practices. Users are freed from having to concern themselves with things like “how do I ship logs” - it just happens based on the administrators setup. Likewise, there’s a strong “bring your own”, lego brick approach to CI, security, schedulers etc. This provides the minimal vendor lock-in, whilst allowing mixed-mode runtimes (e.g. part Mesos, part k8s) that span multiple geographies. If you want to know what your entire service fleet globally looks like, Nelson can tell you.

Some of the primary Nelson features are:

- Deployment against a scheduler of your choice (presently, Nomad + Kube - there was an internal Mesos implementation as well that was never open-sourced; the mechanism is entirely pluggable though. The community has an ECS implementation as well so I hear)

- Automatic policy management with Vault. When you launch something onto a cluster, there is nearly always the problem of policy management and a process flow (often manual) that involves security to sign off and approval for systems accessing certain credentials. Nelson automates this workflow by generating and deploying policies on a per-stack basis. This makes it easy to reason about orphan policies (they don't exist because Nelson cleans them up)

- Lifecycle management for all systems deployed; when a system isn’t logically being used it will be cleaned up. I talk about this in more depth in this presentation: https://www.youtube.com/watch?v=lTIvKZHedJQ

- Topographical security auditing: can answer questions like “show me all the systems available at the edge”, or “show me all systems that use system X”. Every action conducted against Nelson is also stored in an audit trail, so you always know what user did what action, even if that user is Nelson itself (for example, why did Nelson destroy system X).

What you’ll notice here is that Nelson is not providing the following:

- No code build or coverage reporting
- No prescription on your CI system (compared to say, JenkinsX which monolithically couples everything from CI to runtime)
- Strong opinions on how you test and release your code.
- Strong opinions on how your runtime traffic control should work.
- No opinions on how to scale your systems (automatically or otherwise).
- Manual approval workflows.
- Integration into VM-centric workflows (e.g. built-in VM baking).

In this way, Nelson is a very light, container-first platform by comparison - it provides a high-level description of the system you wish to deploy, and then handles the tedium around that. Nelson is not in the runtime hot path, so outages don't matter (those only prevent you deploying more new stuff). Likewise, Nelson is highly compassable - at Verizon we built continuous testing and experimentation systems as discrete, complimentary systems (sadly we never got to open-source those, but the learnings were highly reusable).
