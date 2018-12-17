---
layout: "single-with-toc"
toc: "true"
title: Blueprint Settings
preamble: >
  For an introduction to blueprints, please see the [getting started guide](/getting-started/blueprints.html). This reference covers the available template attributes that blueprint authors can use, along with some examples. For ease of reading, this reference for blueprint fields is broken down into a few logical sections.
menu:
  main:
    identifier: docs-blueprint
    parent: docs
    url: /documentation/blueprints.html
    weight: 6
---

## Units + Deployments

<table class="table table-striped">
  <thead>
    <tr>
      <td width="30%"><strong>Field</strong></td>
      <td><strong>Description</strong></td>
      <td><strong>Context</strong></td>
    </tr>
  </thead>
  <tbody>
    <tr>
      <td><code>stack_name</code></td>
      <td>The stack name for the given deployment, for example <code>foo--1-2-3--xwsdfsf</code></td>
      <td><code>*</code></td>
    </tr>
    <tr>
      <td><code>namespace</code></td>
      <td>The namespace for the given deployment, for example <code>dev</code> or <code>stage</code> etc</td>
      <td><code>*</code></td>
    </tr>
    <tr>
      <td><code>health_check</code></td>
      <td>description</td>
      <td><code>*</code></td>
    </tr>
    <tr>
      <td><code>health_check_interval</code></td>
      <td>description</td>
      <td><code>*</code></td>
    </tr>
    <tr>
      <td><code>health_check_path</code></td>
      <td>description</td>
      <td><code>*</code></td>
    </tr>
    <tr>
      <td><code>health_check_port</code></td>
      <td>description</td>
      <td><code>*</code></td>
    </tr>
    <tr>
      <td><code>health_check_timeout</code></td>
      <td>description</td>
      <td><code>*</code></td>
    </tr>
    <tr>
      <td><code>image</code></td>
      <td>description</td>
      <td><code>*</code></td>
    </tr>
    <tr>
      <td><code>port_name</code></td>
      <td>description</td>
      <td><code>*</code></td>
    </tr>
    <tr>
      <td><code>port_number</code></td>
      <td>description</td>
      <td><code>*</code></td>
    </tr>
    <tr>
      <td><code>ports</code></td>
      <td>description</td>
      <td><code>*</code></td>
    </tr>
    <tr>
      <td><code>ports_list</code></td>
      <td>description</td>
      <td><code>*</code></td>
    </tr>
    <tr>
      <td><code>unit_name</code></td>
      <td>description</td>
      <td><code>*</code></td>
    </tr>
    <tr>
      <td><code>version</code></td>
      <td>description</td>
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
      <td>If supported by your scheudler, what is the maximum number of CPUs tasks derived from this blueprint should be able to use</td>
      <td><code>*</code></td>
    </tr>
    <tr>
      <td><code>cpu_request</code></td>
      <td>If supported by your scheudler, what is the minimum number of CPUs tasks derived from this blueprint should be able to use</td>
      <td><code>*</code></td>
    </tr>
    <tr>
      <td><code>desired_instances</code></td>
      <td>How many copies of the container should the scheudler attempt to run.</td>
      <td><code>*</code></td>
    </tr>
    <tr>
      <td><code>empty_volume_mount_name</code></td>
      <td>description</td>
      <td><code>empty_volumes_list</code></td>
    </tr>
    <tr>
      <td><code>empty_volume_mount_path</code></td>
      <td>description</td>
      <td><code>empty_volumes_list</code></td>
    </tr>
    <tr>
      <td><code>empty_volume_mount_size</code></td>
      <td>description</td>
      <td><code>empty_volumes_list</code></td>
    </tr>
    <tr>
      <td><code>empty_volumes</code></td>
      <td>description</td>
      <td><code>*</code></td>
    </tr>
    <tr>
      <td><code>empty_volumes_list</code></td>
      <td>description</td>
      <td><code>empty_volumes</code></td>
    </tr>
    <tr>
      <td><code>envvar_name</code></td>
      <td>description</td>
      <td><code>envvars_list</code></td>
    </tr>
    <tr>
      <td><code>envvar_value</code></td>
      <td>description</td>
      <td><code>envvars_list</code></td>
    </tr>
    <tr>
      <td><code>envvars</code></td>
      <td>description</td>
      <td><code>*</code></td>
    </tr>
    <tr>
      <td><code>envvars_list</code></td>
      <td>description</td>
      <td><code>envvars</code></td>
    </tr>
    <tr>
      <td><code>memory_limit</code></td>
      <td>If supported by your scheudler, what is the maximum amount of memory tasks derived from this blueprint should be allocated</td>
      <td><code>*</code></td>
    </tr>
    <tr>
      <td><code>memory_request</code></td>
      <td>If supported by your scheudler, what is the minimum amount of memory tasks derived from this blueprint should be allocated</td>
      <td><code>*</code></td>
    </tr>
    <tr>
      <td><code>retries</code></td>
      <td>In the event the container fails to launch, how many times should the scheudler retry before giving up attempting to scheudle the task deriving from this blueprint</td>
      <td><code>*</code></td>
    </tr>
    <tr>
      <td><code>schedule</code></td>
      <td>If the deployment in question had a cron-style scheudle defined in its plan, then this field will hold that value.</td>
      <td><code>*</code></td>
    </tr>
  </tbody>
</table>
