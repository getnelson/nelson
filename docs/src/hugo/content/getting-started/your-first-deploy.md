---
layout: "single-with-toc"
title: Your first deployment
preamble: >
  One of the core tenets of the Nelson philosophy is that all changes are checked into source code - nothing should be actioned out of band, in an entirely untrackable manner. Nelson requires users define a manifest file and check it into the root of the repository. This file is called `.nelson.yml` (note the preceding `.` since it is a UNIX dotfile).
#contents:
#- Command Line
#- Server
#menu:
#  main:
#    parent: gs
#    identifier: gs-firstdeploy
#    url: /getting-started/your-first-deploy.html
#    weight: 2
---

## Container

TODO: show how to make a basic container and publish with slipway

## Manifest

This file contains the application `unit` definitions, along with any additional configuration for monitoring, alerting and scaling those units. Consider a simple example that launches a Hello World service:

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

This is the simplest `.nelson.yml` that one could define for a unit that exposes a service on port 9000. It declares a unit called `hello-world`, and then exposes a port. This is an example of a unit that is typically referred to as a service. By comparison, units that are intended to be run periodically define a `schedule`. Such a unit is typically referred to as a job. Below is a simple example of a unit that is run hourly:

```yaml
---
units:
- name: hello-world
  description: >
    very simple example job that prints
    hello world and exits right away

plans:
- name: dev-plan
  schedule: hourly

namespaces:
- name: dev
  units:
  - ref: howdy
    plans:
    - dev-plan
```

Here the two units are similar, but the second defines a `schedule` under the plans stanza, which indicates that the unit is to be run periodically as a `job`.

## Enable the repo

TODO: show how to enable the repo

## Integrate CI

TODO: show how to use slipway
