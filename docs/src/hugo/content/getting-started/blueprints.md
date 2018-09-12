---
layout: "single"
title: Blueprints
preamble: >
  The *Nelson* manifest enables the user to focus primarily on their own application and not worry about the specifics of runtime scheduling. Whilst this can be largely beneficial, it also presents a set of challenges as more often than not every organization has a different internal process with slight differences. To accommodate this, *Nelson* supports the concept of `Workflow` and `Blueprint`.
#contents:
#- Command Line
#- Server
menu:
  main:
    parent: gs
    identifier: gs-blueprints
    url: /getting-started/blueprints.html
    weight: 5
---


A `Blueprint` is a scheduler specification (for example, Kubernetes YAML document(s) or Nomad HCL) mustache template, which at deploy-time *Nelson* will interpolate with the runtime data (e.g. `StackName`), and submit that rendered output to the scheduler API in order to launch a stack. Whilst the author would hope that this powerful tool is carefully used, it provides the operators / admins of *Nelson* the flexibility to create deployment stacks that use whatever runtime setup they want. For example, Company X might wish to run their logging and routing as sidecars, with Kubernetes pods using particular node affinity, where as Company Y might want to instead just use node selectors for deployments and rely on some node global configuration. *Nelson* simply does not care. Consider the following Kubernetes Blueprint snippet as an example:

```
[...]
{{#ports}}
ports:
{{#ports_list}}
- name: {{port_name}}
  containerPort: {{port_number}}
{{/ports_list}}
{{/ports}}
[...]
```

Here, the blueprint enumerates the `port_list` defined in the supplied Nelson Manifest, translating that definition into the Kubernetes "pod spec" definition of container ports. Please [see the reference section](api.html#blueprints) for more information about Blueprint templating scheme and the variables that are available for interpolation.

## Workflows

A `Workflow` codifies the steps needed to interact with a given scheduling system (and related systems) to get a production-ready deployment. For example, consider the steps below we might manually take to create a deployment:

* Create a stack-specific service account

* Create a Vault auth principle for stack service account

* Render blueprint and send to the scheduler

* Wait for the stack to become ready

* Setup alerting rules in prometheus

It is these kinds of actions and steps, when viewed together, that makeup a workflow. There is never just a single system involved in operational orchestration. At the time of writing, Nelson supports a set of different workflows that support different runtime features:

<table class="table table-striped">
  <thead>
    <tr>
      <td align="center"><strong>&nbsp;</strong></td>
      <td align="center"><strong>canopus</strong></td>
      <td align="center"><strong>pulsar</strong></td>
      <td align="center"><strong>magentar</strong></td>
    </tr>
  </thead>
  <tbody>
    <tr>
      <td><i>Docker</i></td>
      <td align="center">✓</td>
      <td align="center">✓</td>
      <td align="center">✓</td>
    </tr>
    <tr>
      <td><i>Nomad</i></td>
      <td></td>
      <td></td>
      <td align="center">✓</td>
    </tr>
    <tr>
      <td><i>Kubernetes</i></td>
      <td align="center">✓</td>
      <td align="center">✓</td>
      <td></td>
    </tr>
    <tr>
      <td><i>Vault</i></td>
      <td></td>
      <td align="center">✓</td>
      <td align="center">✓</td>
    </tr>
    <tr>
      <td><i>Prometheus</i></td>
      <td></td>
      <td></td>
      <td align="center">✓</td>
    </tr>
  </tbody>
</table>

At the time of writing, Workflows are codified inside of the Nelson codebase. This may change in future versions of Nelson to make it easier to customize without requiring a source change, but today, please visit the Gitter channel or email the <script src="javascript/contact.js"></script>.

For more information on Blueprints, please [checkout the reference](reference.html#blueprints).