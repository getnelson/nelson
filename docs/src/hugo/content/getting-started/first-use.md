---
layout: "single"
title: First Use
preamble: >
  Launching your first system using Nelson is remarkably easy! This guide walks you through how to get-going with Nelson and covers the high-level components needed to get an end to end solution.
contents:
- Command Line
- Server
menu:
  main:
    parent: gs
    identifier: gs-first-use
    url: /getting-started/first-use.html
    weight: 2
---

## Assumptions

Whilst Nelson is typically non-perscriptive, for the unfamiliar user and for the remainder of this article, the following assumptions will be made to avoid confussion:

* You can launch any container you like using Nelson; there is no requirement from the system perspective that the container behaves in a particular way other than having a [typical Nelson entrypoint](https://docs.docker.com/engine/reference/builder/#entrypoint).

* Nelson is setup and running in your environment, and configured to talk to a scheudler of your choosing.

## Manifest

One of the core tenets of the Nelson philosophy is that all changes are checked into source code - nothing should be actioned out of band, resutling in an untrackable system state. Nelson requires users define a manifest file and check it into the root of the source repository. This file is called `.nelson.yml` (note the preceding `.` since it is a UNIX dotfile).

This file contains the application `unit` definitions, along with any additional configuration for monitoring, alerting and scaling those units as needed. Consider this example that launches a Hello World service:

```yaml
---
units:
  - name: hello-world
    description: >
      very simple example service that says
      hello world when the index resource is
      called via http
    ports:
      - default->9000/http

plans:
  - name: default

namespaces:
  - name: dev
    units:
      - ref: hello-world
      - plans:
         - default
```

This is a simplistic `.nelson.yml` that one could define for a unit that exposes a service on port 9000. It declares a unit called `hello-world`, and then exposes a port. This is an example of a unit that is typically referred to as a service - be sure to save this file and check it into the `master` branch of your repository.

Nelson supports both services and jobs with ease; more on configuring these units can be found in the [Manifest section](/documentation/manifest.html) of the documentation.

## Enable the repo

Now that you have a manifest file, we can ask Nelson to enable deployments for a given repository. In practice Nelson is listening for events from Github (commits, pull requests, deployments etc) using a [repository webhook](https://developer.github.com/v3/repos/hooks/) - Nelson keeps track of all the repositories you have access to as a user on Github, but it will periodically require syncing to ensure that Nelson knows about any new repositories. The first time you use Nelson, no repositories will be listed in the system so we'll have to ask Nelson to sync them.

<div class="alert alert-warning" role="alert">
  Be aware that Nelson can only access repositories on the command line for which the provided Github access token used to <a href="/getting-started/install.html">login to Nelson</a> with, has access too.
</div>

To sync your repositories for the first time, run the following:

```
$ nelson repos sync
```

Depending upon the number of organizations and repositories you have access too, the time this operation takes to complete may very. For larger organizations with thousands of repositories this may take a several minutes, but in the common case it should be relatively quick. Once completed, you can list the repositories available in your system like so:

```
$ nelson repos list -o example
```

You should expect to see output that looks something like the following:

```
$ nelson repos list -o timperrett
  REPOSITORY                       OWNER       ACCESS  STATUS
  awesome-nomad                    timperrett  admin   disabled
  baseimage-docker                 timperrett  admin   disabled
  bazel                            timperrett  admin   disabled
  bazel-buildfarm                  timperrett  admin   disabled
```

The listed repos should (upon first use) all show as `disabled`. This indicates that Nelson deployments are *not* setup for that particular repository. In order to enable a repository for deployment, run the following command:

```
$ nelson repos enable \
--organization timperrett \
--repository nelson-example
```

This command will instruct the Nelson server to install a Github webhook on the repository specified, which will send subsequent Github Deployment events to Nelson.

## Integrate with CI

