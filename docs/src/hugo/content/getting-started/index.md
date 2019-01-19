---
layout: "single"
title: "Getting Started"
preamble: |
  This user guide covers the information that a user should make themselves familiar with to both get started with Nelson, and also get the most out of the system. Certain parts of Nelson have been covered in more detail than would be typical for an entry-level user guide, but this is to make sure users are fully aware of the choices they are making when building out their deployment specification.
menu:
  main:
    identifier: gs
    url: /getting-started/index.html
    weight: 2
---

In order to understand the rest of this user guide, there are a set of terms that are useful to understand:

<table class="table table-striped">
  <thead>
    <tr>
      <td width="15%"><strong>Term</strong></td>
      <td><strong>Description</strong></td>
    </tr>
  </thead>
  <tbody>
    <tr>
      <td><em>Repository</em></td>
      <td>References one source code repository: this may contain multiple unit kinds, but represents one canonical release workflow (i.e. all units in the repository are updated and released together).</td>
    </tr>
    <tr>
      <td><em>Blueprint</em></td>
      <td>A scheduler specification that represents a component to be deployed, with variables that will later be interpolated by the executing workflow. In practice, this replaces documented tokens in the template with those provided by Nelson (for example, stack_name)</td>
    </tr>
    <tr>
      <td><em>Scheduler</em></td>
      <td>The element within the datacenter that actually makes work placement decisioning, based upon the available resources (e.g. CPU, RAM etc.).</td>
    </tr>
    <tr>
      <td><em>Datacenter</em></td>
      <td>Represents a single failure domain - it is a <em>logical</em> ascription of some computing resources to which work can be assigned. In practice, a "datacenter" from Nelson's perspective may either be a single <code>scheduler</code> endpoint that directly maps to a single physical datacenter (which internally has redundant power domains), or it could be multiple virtual datacenters within a geographic region (e.g. AWS Availability Zone). The key thing is that Nelson's concept of datacenter is all about scheduling failure modes.</td>
    </tr>
    <tr>
      <td><em>Namespace</em></td>
      <td>Defines a virtual space for units to operate. One namespace cannot talk to a unit in another namespace. The namespace itself represents a collection of units. More often than not, these namespaces take names such as "dev", "qa" and "production". While it is common to think of these as environments, this term was specifically avoided because it typically carries with it the idea of physical separation, which does not exist in a shared computing environment.</td>
    </tr>
    <tr>
      <td><em>Plan</em></td>
      <td>Defines how a unit is to be deployed. It specifies the resource requirements, constraints, and environment variables.</td>
    </tr>
    <tr>
      <td><em>Unit</em></td>
      <td>Defines logically what is to be deployed. Units pertain specifically to the concept of something that is deployable at the high level. For example, one could have a unit <code>accounts</code> or <code>api-gateway</code>. Units are not versioned, and do not discriminate between how they are deployed, i.e. as a service (long lived and not expected to terminate) or job.</td>
    </tr>
    <tr>
      <td><em>Stack Name</em></td>
      <td>Whilst units are a logical concept only, stacks are the direct implementations of units. Specifically, a stack represents a unique deployment of a very particular unit, of a particular version number, along with a uniquely provisioned hash. For example: <code>accounts--2-8-287--roiac45o</code>.</td>
    </tr>
    <tr>
      <td><em>Feature Version</em></td>
      <td>Feature versions are the first two <code>major</code> and <code>minor</code> digits of a <a href="http://semver.org/" target="_blank">semantic version number</a>. Feature versions are always of the form <code>X.Y</code>, for example: <code>1.2</code>, <code>4.6</code> etc.</td>
    </tr>
  </tbody>
</table>
