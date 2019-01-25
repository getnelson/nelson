---
layout: "single"
title: First Use
preamble: >
  Launching your first system using Nelson is remarkably easy! This guide walks you through how to get-going with Nelson and covers the high-level components needed to get an end to end solution.
contents:
- Assumptions
- Manifest
- Enabling Nelson
- Triggering a Deployment
- Working with Stacks
- Further Reading
menu:
  main:
    parent: gs
    identifier: gs-first-use
    url: /getting-started/first-use.html
    weight: 2
---

## Assumptions

Whilst Nelson is typically non-prescriptive, for the unfamiliar user and for the remainder of this article, the following assumptions will be made to avoid confusion:

* If some of the terminology in this guide is unfamiliar, please be sure [to read the glossary](https://getnelson.io/getting-started/) before continuing.

* You can launch any container you like using Nelson; there is no requirement from the system perspective that the container behaves in a particular way other than having a [typical Docker entrypoint](https://docs.docker.com/engine/reference/builder/#entrypoint).

* Nelson is setup and running in your environment, and configured to talk to a scheduler of your choosing.

## Manifest

One of the core tenets of the Nelson philosophy is that all changes are checked into source code - nothing should be actioned out of band, resulting in an untrackable system state. Nelson requires users to define a manifest file and check it into the root of the source repository. This file is called `.nelson.yml` (note the preceding `.` since it is a UNIX dotfile).

This file contains the application `unit` definitions, along with any additional configuration for monitoring, alerting and scaling those units as needed. Consider this example that launches a Hello World service:

```yaml
---
units:
- name: hello-world
  description: >
    very simple example job that
    prints hello world when run
  ports:
    - default->9000/http

plans:
- name: default

namespaces:
- name: dev
  units:
  - ref: hello-world
    plans:
    - default
```

This is a simplistic `.nelson.yml` that one could define for a unit that exposes a service on port 9000. It declares a unit called `hello-world`, and then exposes a port. This is an example of a unit that is typically referred to as a service - be sure to save this file and check it into the `master` branch of your repository.

Nelson supports both services and jobs with ease; more on configuring these units can be found in the [Manifest section](/documentation/manifest.html) of the documentation.

## Enabling Nelson

Now that you have a manifest file, we can ask Nelson to enable deployments for a given repository. In practice Nelson is listening for events from GitHub (commits, pull requests, deployments etc.) using a [repository webhook](https://developer.github.com/v3/repos/hooks/) - Nelson keeps track of all the repositories you have access to as a user on GitHub, but it will periodically require syncing to ensure that Nelson knows about any new repositories. The first time you use Nelson, no repositories will be listed in the system so we'll have to ask Nelson to sync them.

<div class="alert alert-warning" role="alert">
  Be aware that Nelson can only access repositories on the command line for which the provided GitHub access token used to <a href="/getting-started/install.html">login to Nelson</a> with has access to.
</div>

To sync your repositories for the first time, run the following:

```
$ nelson repos sync
```

Depending upon the number of organizations and repositories you have access too, the time this operation takes to complete may vary. For larger organizations with thousands of repositories this may take a several minutes, but in the common case it should be relatively quick. Once completed, you can list the repositories available in your system like so:

```
$ nelson repos list --owner example
```

You should expect to see output that looks something like the following:

```
λ nelson repos list -o getnelson
  REPOSITORY                OWNER      ACCESS  STATUS
  api                       getnelson  admin   disabled
  build-env                 getnelson  admin   disabled
  cardinal                  getnelson  admin   disabled
  cli                       getnelson  admin   disabled
  containers                getnelson  admin   disabled
  getnelson.github.io       getnelson  admin   disabled
  [...]
```

The listed repos should (upon first use) all show as `disabled`. This indicates that Nelson deployments are *not* setup for that particular repository. In order to enable a repository for deployment, run the following command:

```
$ nelson repos enable \
--organization getnelson \
--repository hello-world
```

This command will instruct the Nelson server to install a GitHub webhook on the repository specified, which will send subsequent GitHub Deployment events to Nelson.

## Triggering a Deployment

In most organizations where Nelson is being deployed, [continuous deployment](https://en.wikipedia.org/wiki/Continuous_delivery#Relationship_to_continuous_deployment) is an explicit goal. That is to say, merging changes into the mainline branch should trigger a deployment into the targeted datacenter(s). Nelson is integrated with GitHub for this purpose; listening to [GitHub Deployments](https://developer.github.com/v3/guides/delivering-deployments/) as a trigger for action. You are free to trigger the deployment however you like - Nelson doesn't care, provided the invocation sends the desired [deployable specification](/getting-started/deployables.html). In order to make triggering deployments easy the Nelson project provides a supporting tool called [Slipway](https://github.com/getnelson/slipway). Checkout <a href="/downloads.html">the downloads page</a> to find the latest release of Slipway and Nelson CLI.

Often Slipway is installed and configured by your CI operator / administrator, but there is nothing preventing you from triggering a deployment from your local machine. Here's an example of deploying a previously pushed Git commit: first, we generate the deployable protocol by supplying the name of the container image that we would like to specify as a deployable:

```
slipway gen --format nldp "docker.company.com/app/hello-world:1.2.3"
```

Be aware that the `gen` command may be called any number of times for a given deployment invocation, allowing you to conduct simultaneous deployments of multiple logical units.

<div class="alert alert-warning" role="alert">
  Ensure that the image name you're passing to the <code>gen</code> command <i> exactly matches that which was defined in the manifest</i> created earlier in this guide. When Nelson operates on your deployment, it will fuse the logical definition in your repository with the runtime information supplied with the deployment invocation.
</div>

Next, using the deployable files emitted to the local working directory with the `gen` command, create a GitHub deployment for the tag `1.2.3` using the `deploy` command:

```
slipway deploy -d . --repo getnelson/hello-world --ref 1.2.3
```

Momentarily after this invocation, Nelson should receive the webhook and start to deploy the specified deployable units. Typically it is nice to know how your deployment is doing, and you can do that with a couple of easy commands.

## Working with Stacks

First, check the list of stacks for the `hello-world` application:

```
nelson stacks list -n dev
GUID          NAMESPACE  STACK                         STATUS  PLAN    WORKFLOW  DEPLOYED AT
69e6ebe3efc6  dev        hello-world--1-0-4--416nm3q6  ready   devel   pulsar    2 days ago
```

In this instance, of Nelson, the `hello-world` unit is the only thing deployed, but all the usual shell tricks (`grep`, `awk`, `sort -k` etc.) will work with the Nelson CLI. If your stack is entirely missing from the normal list, it could be that it failed deployment, in which case you can specify just `failed` state units be returned (empty in the example case, because there were no failed stacks).

```
nelson stack list -s failed -n dev
GUID  NAMESPACE  STACK  STATUS  PLAN  WORKFLOW  DEPLOYED AT
```

For information about the lifecycle of stacks, please see the [dedicated lifecycle documentation](/getting-started/lifecycle.html). Once you've found your stack - `failed` or otherwise - grab the `GUID` field and then inspect the stack logs to see exactly what Nelson did with your project:

```
λ nelson stack logs 69e6ebe3efc6
===>> logs for stack 69e6ebe3efc6
2018-11-02T21:43:51.461Z: Pulsar workflow about to start
2018-11-02T21:43:51.484Z: Writing policy to vault: namespace=dev unit=hello-world policy=nelson__dev__hello-world--1-0-4--416nm3q6 datacenter=us-west-2
2018-11-02T21:43:51.529Z: Writing Kubernetes auth role 'hello-world--1-0-4--416nm3q6' to Vault...
2018-11-02T21:43:51.636Z: Instructing us-west-2's scheduler to handle service container
2018-11-02T21:43:52.715Z: =====> Pulsar workflow completed <=====
```

In the event there was a problem launching to the scheduler, writing to Vault, or anything else encompassed by the deployment workflow, those errors will be shown here.

## Further Reading

Nelson has a range of settings and options that can be configured. Users should talk to their administrator / operators in order to understand what is possible, but this documentation site includes a set of useful reading materials:

* [Lifecycle management](/getting-started/lifecycle.html)
* [Available manifest settings](/documentation/manifest.html)
