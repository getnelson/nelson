---
layout: "single"
toc: "false"
title: Manifest Settings
preamble: >
  The manifest is the primary method to control parameters about your deployment: what it should be called, how big it should be, where it should be deployed too etc etc.
contents:
- Datacenters
- Load Balancers
- Namespaces
- Plans
- Notifications
- Units
menu:
  main:
    identifier: docs-manifest
    parent: docs
    url: /documentation/manifest.html
    weight: 5
---

## Datacenters

Given that not all resources may be available in all datacenters, *Nelson* understands that you may at times want to be picky about which particular datacenters you deploy your *units* into. With this in mind, *Nelson* supplies the ability to whitelist and blacklist certain datacenters.

```
datacenters:
  only:
    - portland
    - redding
```

In this example, using `only` forms a whitelist. This would only deploy to our fictitious `portland` and `redding` datacenters.

```
datacenters:
  except:
    - seattle
```

Using the `except` keyword forms a blacklist, meaning that this deployment would deploy everywhere `except` in seattle. The common use case for this would be that `seattle` had not upgraded to a particular bit of platform infrastructure etc.

<h2 id="manifest-load-balancers" data-subheading-of="manifest">Load Balancers</h2>

Load balancers are another top-level manifest declaration. For an overview of the LB functionality, see the [usage guide](index.html#user-guide-lbs). LBs are first declared logically, with the following block:

```
loadbalancers:
  - name: foo-lb
    routes:
      - name: foo
        expose: default->8444/http
        destination: foobar->default
      - name: bar
        expose: monitoring->8441/https
        destination: foobar->monitoring
```

Next - just like units - you need to declare a [plan](#manifest-plans) in order to specify size and scale of the LB. Plans are discussed in-depth [later in this reference](#manifest-plans), but here's an example for completeness:

```
- name: lb-plan
  instances:
    desired: 4
```

Depending on the backend in use (e.g. AWS), various fields in the `plan` will be ignored, so be sure to check with your administrator which fields are required.

<h2 id="manifest-namespaces" data-subheading-of="manifest">Namespaces</h2>

Namespaces represent virtual "worlds" within the shared computing cluster. From a manifest specification perspective, there are a few interesting things that *Namespaces* enable.

<h3 id="manifest-namespace-sequencing" class="linkable">
  Committing
</h3>

During a release event each unit will only be deployed into the default namespace (this is usually `dev`). After the initial release the unit can be deployed into other namespaces by "committing" it. This can be done via the [commit endpoint of the API](#api-units-commit), or [`nelson unit commit` in the CLI](#cli-unit-commit).

In an ideal world, whatever system you use for testing or validation, the user would integrate with the Nelson API so that applications can be automatically committed from namespace to namespace.

<h2 id="manifest-plans" data-subheading-of="manifest">Plans</h2>

When deploying a unit in a given namespace, it is highly probable that the `unit` may require different resource capacity in each target namespace. For example, a unit in the `dev` namespace is unlikely to need the same resources that the same service in a high-scale `production` namespace would need. This is where plans come in, they define resource requirements, constraints, and environment variables.

<h3 id="manifest-plan-specification" class="linkable">
  Plan Specification
</h3>

Plans define resource requirements, constraints, and environment variables, and are applied to units in a given namespace. Take the following example:

```
plans:
  - name: dev-plan
    cpu: 0.25
    memory: 2048

  - name: prod-plan
    cpu: 1.0
    memory: 8192
    instances:
      desired: 10

namespaces:
  - name: dev
    units:
      - ref: foobar
        plans:
          - dev-plan

  - name: qa
    units:
      - ref: foobar
        plans:
          - prod-plan
```

This example defines two plans: `dev-plan` and `prod-plan`. The fact that the words `dev` and `prod` are in the name is inconsequential, they could have been `plan-a` and `plan-b`. Notice that under the namespace stanza each unit references a plan, this forms a contract between the unit, namespace, and plan. The example above can be expanded into the following deployments: the `foobar` unit deployed in the `dev` namespace using the `dev-plan`, and the `foobar` unit deployed in the `production` namespace using the `prod-plan`

As a quick note the `memory` field is expressed in megabytes, and the `cpu` field is expressed in number of cores (where 1.0 would equate to full usage of one CPU core, and 2.0 would require full usage of two cores). If no resources are specified Nelson will default to 0.5 CPU and 512 MB of memory.

<h3 id="manifest-resource-requests-limits" class="linkable">
  Resource Requests and Limits
</h3>

The resources specified by `cpu` and `memory` are generally treated as resource *limits*, or upper bounds on the amount of resources allocated per unit. Some schedulers, like Kubernetes, also support the notion of a resource *request*, or lower bound on the resources allocated. An example of this might be an application that requires some minimum amount of memory to avoid an out of memory error. For schedulers that support this feature, resource requests can be specified with a `cpu_request` and `memory_request` field in the same way `cpu` and `memory` are specified.

```
plans:
  - name: dev-plan
    memory_request: 256
    memory: 512
```

In the event that a request is specified, a corresponding limit must be specified as well. For schedulers that support resource requests, if only a limit if specified then the request is set equal to the limit. For schedulers that do not support resource requests, requests are ignored and only the limits are used.

<h3 id="manifest-matrix-specification" class="linkable">
  Deployment Matrix
</h3>

In order to provide experimenting with different deployment configurations Nelson allows a unit to reference multiple plans for a single namespace. Think of it as a deployment matrix with the form: units x plans. Use cases range from providing different environment variables (for S3 paths) to cpu / memory requirements. In the example below, the unit `foobar` will be deployed twice in the dev namespace, once with `dev-plan-a` and once with `dev-plan-b`.

```
plans:
  - name: dev-plan-a
    cpu: 0.5
    memory: 256
    environment:
       - S3_PATH=some/path/in/s3

  - name: dev-plan-b
    cpu: 1.0
    memory: 512
    environment:
       - S3_PATH=some/different/path/in/s3

namespaces:
  - name: dev
    units:
      - ref: foobar
        plans:
          - dev-plan-a
          - dev-plan-b
```

<h3 id="manifest-namespace-environment-variables" class="linkable">
  Environment Variables
</h3>

Given that every unit is deployed as a container, it is highly likely that configuration may need to be tweaked for deployment into each environment. For example, in the `dev` namespace, you might want a `DEBUG` logging level, but in the `production` namespace you might want only want `ERROR` level logging. To give a concrete example, lets consider the popular logging library [Logback](http://logback.qos.ch/), and a configuration snippet:

```
<?xml version="1.0" encoding="UTF-8"?>
<included>
  <logger name="your.program" level="${CUSTOM_LOG_LEVEL}" />
</included>
```

In order to customize this log level on a per-namespace basis, one only needs to specify the environment variables in the unit specification of the namespace. Here's an example:

```
plans:
  - name: dev-plan
    environment:
       - CUSTOM_LOG_LEVEL=INFO

namespaces:
  - name: dev
    units:
      - ref: foobar
        plans:
          - dev-plan
```

The `.nelson.yml` allows you to specify a dictionary of environment variables, so you can add as many as your application needs. **Never, ever add credentials using this mechanism**. Credentials or otherwise secret information should not be checked into your source repository in plain text. For information on credential handling, see [the credentials section of the user guide](index.html#user-guide-credentials).

<h3 id="manifest-plan-health-checks" class="linkable">
  Scaling
</h3>

The `plan` section of the manifest is where users can specify the scale of deployment for a given `unit`. Consider the following example:

```
plans:
  - name: default-foobar
    cpu: 0.5
    memory: 512
    instances:
      desired: 1
```

At the time of writing *Nelson* does not support auto-scaling. Instead, Nelson relies on "overprovisioned" applications and cost savings in a converged infrastructure where the scheduling sub-systems know how to handle over-subscription.

<h3 id="manifest-plan-health-checks" class="linkable">
  Health Checks
</h3>

Health checks are defined at the service/port level, and multiple health checks can be defined for a single service/port.
Health checks are used by Consul to determine the health of the service. All checks must be passing for a service to be
considered healthy by Consul. Here's an example of defining a health check in the manifest:

```
units:
  - name: foobar
    description: description for the foo service
    ports:
      - default->9000/http

plans:
   - name: dev-plan
     health_checks:
       - name: http-status
         port_reference: default
         protocol: http
         path: "/v1/status"
         timeout: "10 seconds"
         interval: "2 seconds"

namespaces:
  - name: dev
    units:
      - ref: foobar
    plans:
       - dev-plan
```

In this example, foobar service's `default` port will have an http health check that queries the path `/v1/status` every 2 seconds in the dev namespace. Below is an explanation of each field in the `health_checks` stanza:

<table class="table table-striped">
  <thead>
    <tr>
      <td><strong>Field</strong></td>
      <td><strong>Description</strong></td>
    </tr>
  </thead>
  <tbody>
    <tr>
      <td><code>name</code></td>
      <td>A alphanumeric string that identifies the health check, it can contain alphanumeric characters and hyphens.</td>
    </tr>
    <tr>
      <td><code>port_reference</code></td>
      <td>Specifies the port reference which the service is running. This must match up with the port label defined in the ports stanza of units.</td>
    </tr>
    <tr>
      <td><code>protocol</code></td>
      <td>Specifies the protocol for the health check. Valid options are http, https, and tcp.</td>
    </tr>
    <tr>
      <td><code>path</code></td>
      <td>Specifies the path of the HTTP endpoint which Consul will query. The IP and port of the service will be automatically resolved so this is just the relative URL to the health check endpoint. This is required if protocol is http or https.</td>
    </tr>
    <tr>
      <td><code>timeout</code></td>
      <td>Specifies how long Consul will wait for a health check. It is specified like: "10 seconds".</td>
    </tr>
    <tr>
      <td><code>interval</code></td>
      <td>Specifies the frequency that Consul will preform the health check. It is specified like: "10 seconds".</td>
    </tr>
  </tbody>
</table>

<h3 id="manifest-traffic-shift" class="linkable">
  Traffic Shifting
</h3>

Nelson has the ability to split traffic between two deployments when a new deployment is replacing and older one. A
traffic shift is defined in terms of a traffic shift policy and a duration. A traffic shift policy defines a function
which calculates the percentage of traffic the older and newer deployments should receive given the current moment in time.
As time progresses the weights change and Nelson updates the values for the older and newer deployments. It should be
noted that it does not make sense to define a traffic shifting policy for a unit that is run periodically, i.e. define a
`schedule`. Nelson will return a validation error if a traffic shifting policy is defined for such a unit.

At the time of writing there are two traffic shift policies: `linear` and `atomic`. The `linear` policy is a simple function that shifts
traffic from the older version to the newer version linearly over the duration of the traffic shift. The `atomic` policy
shifts traffic all at once to the newer version.

Traffic shifts are defined on a `plan` as it is desirable to use different policies for different namespaces. For
example in the `dev` namepsace one might want to define a `linear` policy with duration of 5 minutes, while in `prod`
the duration is 1 hour.

Below is an example of using traffic shifting:

```
units:
  - name: foobar
    description: description for the foo service
    ports:
      - default->9000/http

plans:
   - name: dev-plan
     traffic_shift:
       policy: atomic
       duration: "5 minutes"

   - name: prod-plan
     traffic_shift:
       policy: linear
       duration: "60 minutes"

namespaces:
  - name: dev
    units:
      - ref: foobar
    plans:
       - dev-plan

  - name: prod
    units:
      - ref: foobar
    plans:
       - prod-plan
```

Finally, Nelson provides a mechanism to reverse a traffic shift if it is observed that the newer version of the
deployment is undesirable. Once a traffic shift reverse is issued, Nelson will begin to shift traffic back to the
older deployed as defined by the traffic shifting policy. The reverse will shift traffic backwards until 100% is
being routed to the older deployment. Once this happens the newer deployment will no longer receive traffic and will be
eventually cleaned up by nelson. No other intervention is needed by the user after a reverse is issued.

<h2 id="manifest-notifications" data-subheading-of="manifest">
  Notifications
</h2>

Nelson can notify you about your deployment results via slack and/or email. Notifications are sent for a deployment when:

* a deployment has successfully completed or failed
* a deployment has been decommissioned

The following is a simple example that configure both email and slack notifications:


```
notifications:
  email:
    recipients:
      - greg@one.verizon.com
      - tim@one.verizon.com
  slack:
    channels:
      - infrastructure
      - devops
```

<h2 id="manifest-units" data-subheading-of="manifest">Units</h2>

A "unit" is a generic, atomic item of work that Nelson will attempt to push through one of its workflows (more on workflows later). Any given Unit represents something that can be deployed as a container, but that has distinct parameters and requirements.

<h3 id="manifest-unit-ports" class="linkable">
  Ports
</h3>

Units can be run either as a long-running process (service) or periodically (job). While services are meant to be long running processes, jobs are either something that needs to be run on a re-occurring schedule, or something that needs to be run once and forgotten about. Jobs are essentially the opposite of long-lived services; they are inherently short-lived, batch-style workloads.

Units that are meant to be long running typically expose a TCP port. A minimal example of a unit that exposes a port would be:

```
- name: foobar-service
  description: description of foobar
  ports:
    - default->8080/http
```

This represents a single service, that exposes HTTP on port `8080`. When declaring ports, the primary application port that is used for routing application traffic must be referred to as `default`. You can happily expose multiple ports, but only the `default` port will be used for automatic routing within the wider system. Any subsystem that wishes to use a non-default port can happily do so, but must handle any associated concerns. For example, one might expose a `monitoring` port that a specialized scheduler could call to collect metrics.

For completeness, here's a more complete example of a service unit that can be used in your `.nelson.yml`:

```
- name: foobar
  description: description of foobar
  workflow: magnetar
  ports:
    - default->8080/http
    - other->7390/tcp
  dependencies:
    - ref: inventory@1.4
    - ref: db-example@1.0
```

The `ports` dictionary items must follow a very specific structure - this is how nelson expresses relationships in ports. Let's break down the structure:

<div class="clearing">
  <img src="images/port-syntax.png" />
  <small><em>Figure 2.3.1: port definition syntax</em></small>
</div>

It's fairly straight forward syntactically, but lets clarify some of the semantics:

<table class="table table-striped">
  <thead>
    <tr>
      <td><strong>Section</strong></td>
      <td><strong>Description</strong></td>
    </tr>
  </thead>
  <tbody>
    <tr>
      <td><code>name</code></td>
      <td>Provides a canonical handle to a given port reference. For example, if your service exposes multiple ports then you may want to give the primary service port the name <code>default</code> whilst also exposing another port called <code>monitoring</code>.</td>
    </tr>
    <tr>
      <td><code>port&nbsp;number</code></td>
      <td>Actual port number that the container has <a href="https://docs.docker.com/engine/reference/builder/#/expose">EXPOSE</a>'d at build time.</td>
    </tr>
    <tr>
      <td><code>protocol</code></td>
      <td>Wire protocol this port is using. Currently supported values are <code>http</code>, <code>https</code> or <code>tcp</code>. This information is largely informational, but may later be used for making routing decisions.</td>
    </tr>
  </tbody>
</table>

<h3 id="manifest-unit-dependencies" class="linkable">
  Dependencies
</h3>

For most units to be useful, it is very common to require another sub-system to successfully operate, and *Nelson* encodes this concept as `dependencies`. Dependencies need to be declared in the unit definition as a logical requirement:

```
- name: foobar-service
  description: description of foobar
  ports:
    - default->8080/http
  dependencies:
    - ref: inventory@1.4
```

The unit name of the dependency can typically be discovered using the *Nelson* CLI command `nelson units list`, which will display the logical dependencies available in the specified datacenter. If you declare a dependency on a system, it **must** exist in every namespace you are attempting to deploy into; in the event this is not the case, *Nelson* will fail to validate your manifest definition.

Having multiple dependencies is common: typically a service or job might depend on a datastore, message queue or another service. Once declared in *Nelson* as a dependency, the appropriate Lighthouse data will also be pushed to the datacenter, which enables the user to dynamically resolve their dependencies within whatever datacenter the application was deployed into.

<h3 id="manifest-unit-resources" class="linkable">
  Resources
</h3>

In addition to depending on internal elements like databases and message queues, any given units can also depend on external services (such as Amazon S3, Google search etc) and are declared under the resources stanza. What makes `resources` different to `dependencies` is that they are explicitly global to the caller. Regardless of where you visit [google.com](https://www.google.com) from, you always access the same service from the callers perspective - the fact that [google.com](https://www.google.com) is globally distributed and co-located in many edge datacenters is entirely opaque.

<div class="alert alert-warning" role="alert">
  It is critical that as a user, you only leverage the <code>resources</code> block for external services. If the feature is abused for internal runtime systems, multi-region and data-locality will simply not work, and system QoS cannot be ensured.
</div>

A resource consists of a name and an optional description. While it is always useful to declare an external dependency, it is required if credential
provisioning is needed (i.e. S3).


```
- name: foo
  description: description of foobar
  ports:
    - default->8080/http
  resources:
    - name: s3
      description: image storage
```

All resources must define a uri associated with it. Because a uri can be different between namespaces it is defined under the plans stanze. A use case for this is using a different bucket between qa and dev.

```
plans:
  - name: dev
    resources:
      - ref: s3
        uri: s3://dev-bucket.organization.com

  - name: qa
     resources:
       - ref: s3
         uri: s3://qa-bucket.orgnization.com

```

<h3 id="manifest-unit-schedules" class="linkable">
  Schedules
</h3>

Units that are meant to be run periodically define a `schedule` under the plans stanza. They can also optionally declare `ports` and `dependencies`. The example below will be run daily in dev and hourly in prod.

```
units:
  - name: foobar-batch
    description: description of foobar
    dependencies:
      - ref: inventory@1.4

plans:
  - name: dev
    schedule: daily

  - mame: prod
    schedule: hourly

namespaces:
  - name: dev
    units:
      - ref: foobar-batch
    plans:
       - dev-plan

  - name: dev
    units:
      - ref: foobar-batch
    plans:
       - prod-plan
```

Possible values for `schedule` are: `monthly`, `daily`, `hourly`, `quarter-hourly`, and `once`. These values can be mapped to the following cron expressions:

```
 monthly        -> 0 0 1 * *
 daily          -> 0 0 * * *
 hourly         -> 0 * * * *
 quarter-hourly -> */15 * * * *
```

The value `once` is a special case and indicates that the job will only be run once. If more control over a job's schedule is needed the `schedule` field can be defined directly with any valid cron expression.

```
plans:
  - mame: dev
    schedule: "*/30 * * * *"
```

The more powerful cron expression is typically useful when you require a job to run at a specific point in the day, perhaps to match a business schedule or similar.

<h3 id="manifest-unit-workflows" class="linkable">
  Workflows
</h3>

Workflows are a core concept in *Nelson*: they represent the sequence of actions that should be conducted for a single deployment `unit`. Whilst users cannot define their own workflows in an arbitrary fashion, each and every `unit` has the chance to reference an alternative workflow by its name. However, at the time of this writing there is only a single workflow (referenced as `magnetar`) for all `units`. The `magnetar` workflow first replicates the the required container to the target datacenter, and then attempts to launch the `unit` using the datacenters pre-configured scheduler. Broadly speaking this should be sufficient for the majority of deployable units.

If users require a specialized workflow, please contact the *Nelson* team to discuss your requirements.

<h3 id="manifest-unit-expiration-policies" class="linkable">
  Expiration Policies
</h3>

Nelson manages the entire deployment lifecycle including cleanup. Deployment cleanup is triggered via the deployments expiration date which is managed by the `expiration_policy` field in the plans stanza. The `expiration_policy` defines rules about when a deployment can be decommissioned. Below is a list of available expiration policies:

<table class="table table-striped">
  <thead>
    <tr>
      <td><strong>Reference</strong></td>
      <td><strong>Description</strong></td>
    </tr>
  </thead>
  <tbody>
    <tr>
      <td><code>retain-active</code></td>
      <td>Retain active is the default expiration policy for services. It retains all versions with an active incoming dependency</td>
    </tr>
    <tr>
      <td><code>retain-latest</code></td>
      <td>Retain latest is the default expiration policy for jobs. It retains the latest version</td>
    </tr>
    <tr>
      <td><code>retain-latest-two-major</code></td>
      <td>Retain latest two major is an alternative policy for jobs. It retains latest two major versions, i.e. 2.X.X and 1.X.X</td>
    </tr>
    <tr>
      <td><code>retain-latest-two-feature</code></td>
      <td>Retain latest two feature is an alternative policy for jobs. It retains the latest two feature versions, i.e. 2.3.X and 2.2.X</td>
    </tr>
  </tbody>
</table>

Nelson does not support manual cleanup, by design. All deployed stacks exist on borrowed time, and will (eventually) expire. If you do not explicitly choose an expiration policy (this is common), then Nelson will apply some sane defaults, as described in the preceding table.

<h3 id="manifest-unit-meta" class="linkable">
  Meta Tags
</h3>

Sometimes you might want to "tag" a unit with a given set of meta data so that later you can apply some organization-specific auditing or fiscal tracking program. In order to do this, Nelson allows you to tag - or label - your units. Here's an example:

```
units:
- name: example
  description: example
  ports:
  - default->9000/http
  meta:
  - foobar
  - buzzfiz
```

Meta tags may not be longer than 14 characters in length and can only use characters that are acceptable in a DNS name.

<h3 id="manifest-unit-alerting" class="linkable">
  Alerting
</h3>

Alerting is defined per unit, and then deployed for each stack that is created for that unit. Nelson is not tied to a particular alerting system, and as with the integrations with the cluster managers, Nelson can support integration with multiple monitoring and alerting backends. At the time of writing, Nelson only supports [Prometheus](https://prometheus.io/) as a first-class citizen. This is a popular monitoring and alerting tool, with good throughput performance that should scale for the majority of use cases.

From a design perspective, Nelson is decoupled from the actual runtime, and is not in the "hot path" for alerting. Instead, Nelson acts as a facilitator and will write out the specified alerting rules to keys in the Consul for the datacenter in question. In turn, it is expected that the operators have setup [consul-template](https://github.com/hashicorp/consul-template) (or similar) to respond to the update of the relevant keys, and write out the appropriate configuration file. In this way, Nelson delegates the actual communication / implementation of how the rules are ingested to datacenter operations.

The following conventions are observed when dealing with alerts:

1. Alerts are always defined for a particular unit.
1. All alerts are applied to the unit in any namespace, except those specified by opt-outs in the plan.
1. Notification rules must reference pre-existing notification groups, which are typically configured by your systems administrator, based on organizational requirements.

Let's consider an example, that expands on the earlier explanations of the unit syntax:

```
---
units:
  - name: foobar
    description: >
      description of foobar
    ports:
      - default->8080/http
    alerting:
      prometheus:
        alerts:
        - alert: instance_down
          expression: >-
            IF up == 0
            FOR 5m
            LABELS { severity = "page" }
            ANNOTATIONS {
              summary = "Instance {{ $labels.instance }} down",
              description = "{{ $labels.instance }} of job {{ $labels.job }} down for 5 minutes.",
            }
        rules:
          - rule: "job_service:rpc_durations_microseconds_count:avg_rate5m"
            expression: "avg(rate(rpc_durations_microseconds_count[5m])) by (job, service)"
```

The observant reader will notice the `alerting.prometheus` dictionary that has been added. When using the support for Prometheus, Nelson allows you to specify the native Prometheus alert definition syntax inline with the rest of your manifest. You can use any valid Prometheus alert syntax, and the alert definitions will be automatically validated using the Prometheus binary before being accepted by Nelson.

Whilst the alerting support directly exposes an underlying integration to the user-facing manifest API, we made the choice to expose the complete power of the underlying alerting system, simply because the use cases for monitoring are extremely varied, and having Nelson attempt to "translate" arbitrary syntax for a third-party monitoring system seem tedious and low value. We're happy with this trade off overall as organization change their monitoring infrastructure infrequently, so whilst its a "moving target" over time, it is slow moving.

<h4 id="manifest-unit-alerting-syntax">
  Alert Syntax
</h4>

Upon first glace, the alert block in the manifest can seem confusing. Thankfully, there are only three sections a user needs to care about. The table below outlines the alert definitions.

<table class="table">
  <thead>
    <tr>
      <td><strong>Dictionary</strong></td>
      <td><strong>Description</strong></td>
    </tr>
  </thead>
  <tbody>
    <tr>
      <td><code>prometheus.alerts</code></td>
      <td>An array of alert rules for your unit.  An alert rule is split into two keys: the <code>alert</code> and the <code>expression</code>.</td>
    </tr>
    <tr>
      <td><code>prometheus.alerts[...].alert</code></td>
      <td>The name of the alert. This alert name is used as a key for <a href="#alert-opt-outs">opt-outs</a> and appears throughout the alerting system. Select a name that will make sense to you when you get paged at 3am. The alert name should be in snake case, and must be unique within the unit. Valid characters are <code>[A-Za-z0-9_]</code>.</td>
    </tr>
    <tr>
      <td><code>prometheus.alerts[...].expression</code></td>
      <td>Prometheus expression that defines the alert rule. An alert expression always begins with `IF`. Please see the <a href="https://prometheus.io/docs/alerting/rules/">Prometheus documentation</a> for a full discussion of the expression syntax. We impose the further constraint that all alert expressions come with at least one annotation.</code>.</td>
    </tr>
  </tbody>
</table>

<h4 id="manifest-unit-alerting-rules-syntax">
  Rule Syntax
</h4>

In addition to specification of alerts, the manifest also allows for the specification of Prometheus rules. See the <a href="https://prometheus.io/docs/querying/rules/#recording-rules">Prometheus documentation on recording rules</a> for a discussion on the differences between alerts and recording rules.

<table class="table">
  <thead>
    <tr>
      <td><strong>Dictionary</strong></td>
      <td><strong>Description</strong></td>
    </tr>
  </thead>
  <tbody>
  <tr>
    <td><code>prometheus.rules</code></td>
    <td>An array of recording rules for your unit. <a href="https://prometheus.io/docs/querying/rules/#recording-rules">Recording rules</a> pre-calculate more complicated, expensive expressions for use in your alerting rules.  Like alert rules, each recording rule is split into two keys: <code>rule</code> and <code>expression</code>.</td>
  </tr>
  <tr>
    <td><code>prometheus.rules[...].rule</code></td>
    <td>Name of the recording rule.  It should be in snake case and unique within the unit.  Valid characters are <code>[A-Za-z0-9_]</code>.</td>
  </tr>
  <tr>
    <td><code>prometheus.rules[...].expression</code></td>
    <td>Prometheus expression that defines the recording rule. See the documentation for the <a href="https://prometheus.io/docs/querying/basics/">Prometheus query language</a> to get an overview of what sort of expressions are possible.</td>
  </tr>
</tbody>
</table>


<h4 id="manifest-unit-alerting-opt-out">
  Opting Out
</h4>

A good alert is an actionable alert.  In some cases, an alert may not be actionable in a given namespace.  A development team might know that quirks in test data in the qa namespace result in high request latencies, and any alerts on such are not informative.  In this case, an alert may be opted out via the `alert_opt_outs` array for the unit.

```
units:
  - name: foobar
    description: >
      description of foobar
    ports:
      - default->8080/http
    dependencies:
      - ref: cassandra@1.0
    alerting:
      prometheus:
        alerts:
          - alert: api_high_request_latency
            expression: >-
              IF ...

plans:
  - name: qa-plan
    cpu: 0.13
    memory: 2048
    alert_opt_outs:
       - api_high_request_latency

  - name: prod-plan
    cpu: 0.65
    memory: 2048

namespaces:
  - name: qa
    units:
      - ref: foobar
        plans:
          - qa-plan

  - name: production
    units:
      - ref: foobar
        plans:
          - prod-plan
```

In the above example, the `api_high_request_latency` alert for the `foobar` unit is opted out in the `qa` namespace.  The alert will still fire in `dev` and `prod` namespaces.  A key in the `alert_opt_outs` array must refer to an `alert` key under `alerting.prometheus.alerts` in the corresponding unit, otherwise Nelson will raise a validation error when trying to parse the supplied manifest.
