---
layout: "single"
toc: "true"
title: Blueprint Settings
preamble: >
  For an introduction to blueprints, please see the [getting started guide](/getting-started/blueprints.html). This reference covers the available template attributes that blueprint authors can use, along with some examples. For ease of reading, this reference for blueprint fields is broken down into a few logical sections.
contents:
- Units and Deployments
- Plans
menu:
  main:
    identifier: docs-blueprint
    parent: docs
    url: /documentation/blueprints.html
    weight: 6
---

The Blueprint system uses [mustache templating](http://mustache.github.io/) under the hood, which is available to operators to specify the Blueprint definitions. This is convenient as it allows users to specify logic-free templates, without prescribing how or where workloads should be deployed. From Nelson's perspective, it simply applies the template to produce a byte stream, and it sends that to the specified target scheduler, without explicitly knowing anything about the contents.

The renderable attributes typically take the following fashion:

#### Values

The most simplistic values to render are `String` and `Int`. In both of these cases, these simply work by specifying a reference to the field you want to render.

```
// render a field as a string
stackName: "{{stack_name}}"
// render a numeric field
completions: {{desired_instances}}
```

#### Enumerators

Some of the more interesting cases come in the form of an enumerator. In general, these work with a "context block", which allows you to specify content in terms of `key:[item, item]`.

```
{{#envvars}}
env:
  {{#envvars_list}}
  - name: "{{envvar_name}}"
    value: "{{envvar_value}}"
  {{/envvars_list}}
{{/envvars}}
```

In this example, we would say that `envvars` has the context `*`, because it can be used anywhere. Comparatively, the `envvars_list` can only be used in the context `envvars` because it is not otherwise accessible.

## Units and Deployments

<table class="table table-striped">
  <thead>
    <tr>
      <td width="28%"><strong>Field</strong></td>
      <td><strong>Description</strong></td>
      <td width="28%"><strong>Context</strong></td>
    </tr>
  </thead>
  <tbody>
    <tr>
      <td><code>stack_name</code></td>
      <td>The stack name for the given deployment, for example <code>foo--1-2-3--xwsdfsf</code>.</td>
      <td><code>*</code></td>
    </tr>
    <tr>
      <td><code>namespace</code></td>
      <td>The namespace for the given deployment, for example <code>dev</code> or <code>stage</code> etc.</td>
      <td><code>*</code></td>
    </tr>
    <tr>
      <td><code>health_check</code></td>
      <td>Block context for everything related to the array of health checks specified in the manifest.</td>
      <td><code>*</code></td>
    </tr>
    <tr>
      <td><code>health_check_interval</code></td>
      <td>How frequently should we execute this check.</td>
      <td><code>*</code></td>
    </tr>
    <tr>
      <td><code>health_check_path</code></td>
      <td>What HTTP path should we check for successful response in the event we are probing a HTTP endpoint.</td>
      <td><code>*</code></td>
    </tr>
    <tr>
      <td><code>health_check_port</code></td>
      <td>What port should the health check attempt to access in the event we are probing a TCP endpoint.</td>
      <td><code>*</code></td>
    </tr>
    <tr>
      <td><code>health_check_timeout</code></td>
      <td>How long should the specified health check wait before declaring itself failed.</td>
      <td><code>*</code></td>
    </tr>
    <tr>
      <td><code>image</code></td>
      <td>When Nelson resolves the actual image that should be used for this deployment at runtime, its value will be populated into this field. This image name will always be a fully-qualified name adhering to the <a href="https://github.com/moby/moby/blob/master/image/spec/v1.md">Docker image specification</a> for repositories and tags.</td>
      <td><code>*</code></td>
    </tr>
    <tr>
      <td><code>ports</code></td>
      <td>Block context for everything related to the array of ports specified in the manifest.</td>
      <td><code>*</code></td>
    </tr>
    <tr>
      <td><code>ports_list</code></td>
      <td>Handle for traversing the port list, and accessing the <code>port_number</code> and <code>port_name</code> fields.</td>
      <td><code>ports</code></td>
    </tr>
    <tr>
      <td><code>port_name</code></td>
      <td>Human-readable label for a given port, ascribed in the manifest. For example, <code>default</code> or <code>http</code> etc.</td>
      <td><code>ports_list</code></td>
    </tr>
    <tr>
      <td><code>port_number</code></td>
      <td>Integer number corresponding to the port declared in the manifest file for a given label.</td>
      <td><code>ports_list</code></td>
    </tr>
    <tr>
      <td><code>unit_name</code></td>
      <td>Name of the unit you are attempting to deploy. For example, <code>foo</code> or <code>foo-bar</code>. This must never include spaces or special characters and must adhere to sanitization per <a href="https://tools.ietf.org/html/rfc1035">IETF RFC 1035</a>.</td>
      <td><code>*</code></td>
    </tr>
    <tr>
      <td><code>version</code></td>
      <td>The version of this particular unit being deployed. Typically a semantic version of the form <code>1.2.3</code>, <code>4.53.44</code> etc.</td>
      <td><code>*</code></td>
    </tr>
  </tbody>
</table>

## Plans

<table class="table table-striped">
  <thead>
    <tr>
      <td width="28%"><strong>Field</strong></td>
      <td><strong>Description</strong></td>
      <td width="28%"><strong>Context</strong></td>
    </tr>
  </thead>
  <tbody>
    <tr>
      <td><code>cpu_limit</code></td>
      <td>The maximum number of CPU cores tasks derived from this blueprint should be able to use. Your chosen scheduler may or may not support this.</td>
      <td><code>*</code></td>
    </tr>
    <tr>
      <td><code>cpu_request</code></td>
      <td>The minimum number of CPU cores tasks derived from this blueprint should be able to use. Your chosen scheduler may or may not support this.</td>
      <td><code>*</code></td>
    </tr>
    <tr>
      <td><code>desired_instances</code></td>
      <td>How many copies of the container should the scheduler attempt to run.</td>
      <td><code>*</code></td>
    </tr>
    <tr>
      <td><code>empty_volumes</code></td>
      <td>Block context for everything related to the array of volumes specified in the manifest.</td>
      <td><code>*</code></td>
    </tr>
    <tr>
      <td><code>empty_volumes_list</code></td>
      <td>Handle for traversing the volume list, and accessing the <code>empty_volume_mount_size</code>, <code>empty_volume_mount_name</code>, and <code>empty_volume_mount_path</code> fields.</td>
      <td><code>empty_volumes</code></td>
    </tr>
    <tr>
      <td><code>empty_volume_mount_name</code></td>
      <td>What should the logical name of the volume mount be.</td>
      <td><code>empty_volumes_list</code></td>
    </tr>
    <tr>
      <td><code>empty_volume_mount_path</code></td>
      <td>What filesystem path should the volume be mounted to.</td>
      <td><code>empty_volumes_list</code></td>
    </tr>
    <tr>
      <td><code>empty_volume_mount_size</code></td>
      <td>The specified volume size defined in megabytes.</td>
      <td><code>empty_volumes_list</code></td>
    </tr>
    <tr>
      <td><code>envvars</code></td>
      <td>Block context for everything related to the array of environment variables specified in the manifest file.</td>
      <td><code>*</code></td>
    </tr>
    <tr>
      <td><code>envvars_list</code></td>
      <td>Handle for traversing the environment variable list, and accessing the <code>envvar_name</code> and <code>envvar_value</code> fields.</td>
      <td><code>envvars</code></td>
    </tr>
    <tr>
      <td><code>envvar_name</code></td>
      <td>The name of the specified environment variable.</td>
      <td><code>envvars_list</code></td>
    </tr>
    <tr>
      <td><code>envvar_value</code></td>
      <td>The value ascribed to a given environment variable.</td>
      <td><code>envvars_list</code></td>
    </tr>
    <tr>
      <td><code>memory_limit</code></td>
      <td>The maximum amount of memory tasks derived from this blueprint should be allocated. Your chosen scheduler may or may not support this.</td>
      <td><code>*</code></td>
    </tr>
    <tr>
      <td><code>memory_request</code></td>
      <td>The minimum amount of memory tasks derived from this blueprint should be allocated. Your chosen scheduler may or may not support this.</td>
      <td><code>*</code></td>
    </tr>
    <tr>
      <td><code>retries</code></td>
      <td>How many retries should the scheduler attempt before giving up on a particular task.</td>
      <td><code>*</code></td>
    </tr>
    <tr>
      <td><code>schedule</code></td>
      <td>Either a predefined key word associated with a specific cron schedule or a cron-style string as defined in the <a href="https://getnelson.io/documentation/manifest.html#manifest-unit-schedules">manifest documentation</a>.</td>
      <td><code>*</code></td>
    </tr>
  </tbody>
</table>
