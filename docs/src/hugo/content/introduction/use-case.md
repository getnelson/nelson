---
layout: "single-with-toc"
toc: "false"
title: "Use Cases"
preamble: >
  Nelson is designed to handle a specific set of use-cases; comprehending these use-cases will allow you to understand if Nelson is a good fit for the challenges you are looking to solve.
menu:
  main:
    parent: intro
    identifier: intro-use-case
    url: /introduction/use-case.html
    weight: 2
---

## Deployment

At its core, Nelson is a system that deploys containers to scheudlers like Kubernetes and Nomad. This alone however does not make Nelson unique: those tools provide a means to deploy containers in a myriad of different ways, and theirin lies the source of a great deal of friction. Scheduler interfaces expose a discrete set of trade-offs to the user, and more often than not they make hard things easier, but easy things hard. This is typically not what most organizations are looking for: easy things should be easy. Nelson provides users a simple interface to deploy the What, Where and How of their deployment.

## Dependency Graph

Topographical security auditing: can answer questions like “show me all the systems available at the edge”, or “show me all systems that use system X”. Every action conducted against Nelson is also stored in an audit trail, so you always know what user did what action, even if that user is Nelson itself (for example, why did Nelson destroy system X).

## Policy Management

Automatic policy management with Vault. When you launch something onto a cluster, there is nearly always the problem of policy management and a process flow (often manual) that involves security to sign off and approval for systems accessing certain credentials. Nelson automates this workflow by generating and deploying policies on a per-stack basis. This makes it easy to reason about orphan policies (they don't exist because Nelson cleans them up).

## Unopinionated CI

Nelson does not put any constraints on the CI system you choose to use. Jenkins, Travis, etc... use whatever you want! This is a crucial design choice as it means that Nelson is not trying to own other parts of your system, making it easier to integrate into multi-team, multi-CI workflows.
