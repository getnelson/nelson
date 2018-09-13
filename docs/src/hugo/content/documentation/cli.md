---
layout: "single"
toc: "false"
title: Command Line
preamble: >
  For installation instructions, see the [user guide](/getting-started/install.html) section. This reference covers the set of operations supplied by the CLI.
contents:
- System Operations
- Datacenter Operations
- Unit Operations
- Stack Operations
- Tips
menu:
  main:
    identifier: docs-cli
    parent: docs
    url: /documentation/cli.html
    weight: 5
---

## System Operations

*Nelson* provides a set of system operations that allow you to get a set of informational data about your session

### Who Am I

Who are you currently logged in as and to which server? Just ask! This is useful when you're not sure which remote backend you're talking too.

```
λ nelson whoami
===>> Currently logged in as Jane Doe @ https://nelson.example.com
```

### Cleanup Policies

When you need to know what cleanup policies are available on this instance of *Nelson*, run the following:

```
λ nelson system cleanup-policies
POLICY                     DESCRIPTION
retain-always              retains forever, or until someone manually decommissions
retain-latest              retains the latest version
retain-active              retains all active versions; active is an incoming dependency
retain-latest-two-feature  retains the latest two feature versions, i.e. 2.3.X and 2.2.X
retain-latest-two-major    retains the latest two major versions, i.e. 2.X.X and 1.X.X
```

### Login

When logging into *Nelson*, there are a variety of options and ways in which to use the command line. Generally speaking, it is recommended that the Github PAT that you setup during the installation is set to the environment variables `GITHUB_TOKEN`, but if you don't want to do that, supplying it explicitly is also supported. Typical usage would look like this:

```
# read token from environment variable GITHUB_TOKEN, but supply explicit host
$ nelson login nelson.yourdomain.com
```

But the following additional options are also availabe. Note that the `--disable-tls` option should only ever be used for local development, and is not intended for regular usage.

```
# fully explicit login
λ nelson login --token 1f3f3f3f3 nelson.yourdomain.com

# read token from env var GITHUB_TOKEN and host from NELSON_ADDR
λ nelson login

# for testing with a local server, you can do:
λ nelson login --disable-tls --token 1f3f3f3f3 nelson.local:9000
```

<hr />

## Datacenter Operations

The Nelson service would have been configured by your system administrator for use with one or more datacenters. These datacenters are your deployment targets.

```
# list the available nelson datacenters
λ nelson datacenters list
  DATACENTER      NAMESPACES
  massachusetts   dev
  texas           dev

# just an alias for the above
λ nelson dcs list
```

Note how the resulting list enumerates the namespaces available in that datacenter. In the event multiple namespaces are available, they will be collapsed into a comma-delimited list.

<hr />

## Unit Operations

As a `unit` in Nelson parlance is a logical concept, the client allows you to query the Nelson instance for units, regardless of the stack they are a part of.

### Listing

This is typically very useful when building out (or upgrading) your own unit that depends on another unit, and you need to know what versions are currently available, or in a given state. The following is an example:

```
λ nelson units list --namespaces dev --datacenters massachusetts
  GUID          NAMESPACE  UNIT              VERSION
  a0d3e7843856  dev      heydiddlyho-http  0.33
  c0e4281e9c9d  dev      howdy-http        0.38
```

The `unit list` subcommand comes with a variety of switches and options, for your convenience:

<table class="table table-striped">
  <thead>
    <tr>
      <td><strong>Option</strong></td>
      <td><strong>Alias</strong></td>
      <td><strong>Required</strong></td>
      <td><strong>Default</strong></td>
      <td><strong>Description</strong></td>
    </tr>
  </thead>
  <tbody>
    <tr>
      <td width="19%"><code>--namespaces</code></td>
      <td width="12%"><code>-ns</code></td>
      <td>Yes</td>
      <td width="17%"><code>n/a</code></td>
      <td>Specify the one or more namespaces that you wish to query. Accepts a command delimited list, for example: `dev,qa,prod`. Note that the comma-delimited list must not contain any spaces</td>
    </tr>
    <tr>
      <td><code>--datacenters</code></td>
      <td><code>-d</code></td>
      <td>No</td>
      <td><code>*</code></td>
      <td>Specify the one or more datacenters you're interested. It is excepted that users would typically not have to specify this switch, as usually users care about overall availability, not the presence of a given datacenter.</td>
    </tr>
    <tr>
      <td><code>--statuses</code></td>
      <td><code>-s</code></td>
      <td>No</td>
      <td><code>warming</code><br />
          <code>active</code><br/>
          <code>deprecated</code></td>
      <td>Specify the one or more statuses that you want the unit to be in. The valid options are <code>pending</code>, <code>deploying</code>, <code>warming</code>,<code>ready</code>, <code>deprecated</code>, <code>garbage</code>, <code>failed</code>, <code>terminated</code></td>
    </tr>
  </tbody>
</table>

These options can be combined or committed in a variety of ways:

```
# show the units deployed in a given datacenter
λ nelson units list --namespaces dev --datacenters massachusetts

# show the units available in several datacenters
λ nelson units list -ns dev --datacenters massachusetts,california

# show the units available in all datacenters for a given namespace
λ nelson units list --namespaces dev

# show the units available in all datacenters for a given namespace and status
λ nelson units list --namespaces dev --statuses deploying,active,deprecated

# show the units that have been terminated by nelson in a given namespace
λ nelson units list --namespaces dev --statues terminated
```

### Commit

Commit a unit@version combination to a specific target namespace. The unit must be associated with the target namespace in the `namespaces` section of the manifest.

```
namespaces:
  - name: dev
    units:
      - ref: howdy-http
  - name: qa
    units:
      - ref: howdy-http
```

To promote version 0.38.145 of howdy-batch to qa, issue the following command:

```
# Commits unit howdy, version 0.38.145 to the qa namespace
λ nelson unit commit --unit howdy-http --version 0.38.145 --target qa
```

See [Committing](#manifest-namespace-sequencing) for more.

### Inspection

If you know the GUID of the unit you're interested in, you can actually get a much more detailed view of it, by using the `inspect` command.

<div class="alert alert-warning" role="alert">
⛔ Currently not implemented. Coming soon!
</div>

<hr />

## Stack Operations

The primary operation most users will want the CLI for is to find a stack that was recently deployed. With this in mind there are a variety of operations available for stacks.

### Listing

Just like listing units, listing stacks is super easy:

```
λ nelson stacks list --namespaces dev --datacenters massachusetts
  GUID          NAMESPACE  PLAN     STACK                            WORKFLOW  DEPLOYED AT
  d655dc6a5799  dev        dev-plan howdy-batch--0-38-145--eov7446m  default   2016-07-15T11:37:08-07:00
```

The `stack list` subcommand also comes with a variety of switches and options, for your convenience:

<table class="table table-striped">
  <thead>
    <tr>
      <td><strong>Option</strong></td>
      <td><strong>Alias</strong></td>
      <td><strong>Required</strong></td>
      <td><strong>Default</strong></td>
      <td><strong>Description</strong></td>
    </tr>
  </thead>
  <tbody>
    <tr>
      <td width="19%"><code>--namespaces</code></td>
      <td width="12%"><code>-ns</code></td>
      <td>Yes</td>
      <td width="17%"><code>n/a</code></td>
      <td>Specify the one or more namespaces that you wish to query. Accepts a command delimited list, for example: `dev,qa,prod`. Note that the comma-delimited list must not contain any spaces</td>
    </tr>
    <tr>
      <td><code>--datacenters</code></td>
      <td><code>-d</code></td>
      <td>No</td>
      <td><code>*</code></td>
      <td>Specify the one or more datacenters you're interested in. It is expected that users would typically not have to specify this switch, as usually users care about overall availability, not the presence of a given datacenter.</td>
    </tr>
    <tr>
      <td><code>--statuses</code></td>
      <td><code>-s</code></td>
      <td>No</td>
      <td><code>warming</code><br />
          <code>ready</code><br/>
          <code>deprecated</code></td>
      <td>Specify the one or more statuses that you want the returned stacks to be in. The valid options are <code>pending</code>, <code>deploying</code>, <code>warming</code>,<code>ready</code>, <code>deprecated</code>, <code>garbage</code>, <code>failed</code>, <code>terminated</code>, any of which can be supplied, in any order.</td>
    </tr>
  </tbody>
</table>

These options can be combined or committed in a variety of ways:

```
# show the stacks deployed in a given datacenter
λ nelson stacks list --namespaces dev --datacenters massachusetts

# show the stacks availabe in several datacenters
λ nelson stacks list --namespaces dev --datacenters massachusetts,california

# show the stacks available in all datacenters for a given namespace
λ nelson stacks list --namespaces dev

# show the stacks available in all datacenters for a given namespace and status
λ nelson stacks list --namespaces dev --statuses deploying,ready,deprecated

# show the stacks that have been terminated by nelson in a given namespace
λ nelson stacks list --namespaces dev --statues terminated

```

### Inspection

If you know the GUID of the stack you're interested in, you can get a much more detailed view of it, by using the `inspect` command. The following is an example:

```
λ nelson stacks inspect ec695f081fbd
===>> Stack Information
  PARAMATER     VALUE
  GUID:         ec695f081fbd
  STACK NAME:   search-http--1-0-28--t82nitd5
  NAMESPACE:    dev
  PLAN:         dev-plan
  WORKFLOW:     magnetar
  DEPLOYED AT:  2016-07-14T08:39:51-07:00
  EXPIRES AT:   2016-08-05T12:11:49-07:00

===>> Dependencies
  GUID          STACK                            WORKFLOW  DEPLOYED AT                DIRECTION
  f8a6d4f03bf3  es-search--1-7-5--58e6ad5e8cf2   default   2016-07-14T08:00:55-07:00  OUTBOUND

===>> Status History
  STATUS     TIMESTAMP                 MESSAGE
  active     2016-07-14T15:41:13.113Z  search-http deployed to massachusetts
  deploying  2016-07-14T15:41:13.068Z  writing alert definitions to massachusetts's consul
  deploying  2016-07-14T15:41:13.021Z  waiting for the application to become ready
  deploying  2016-07-14T15:41:05.089Z  instructing massachusetts's marathon to launch container
  deploying  2016-07-14T15:39:52.077Z  replicating search-http-1.0:1.0.28 to remote registry
  pending    2016-07-14T15:39:52.018Z
```

As you can see, this is significantly more information than the listing table. Whilst the stack information gets you mostly what you knew from the listing table, the dependencies section shows you both who this stack depends on, and also which other stacks depend on this stack. The Status History section shows a summary of the discrete steps the deployment went through and the times between each step.

### Logs

During deployment, it is frequently useful to see exactly what Nelson was doing when it executed the deployment workflow. For this, we provide the logs command.

```
λ nelson stacks logs f81c7a504bf6
===>> logs for stack f81c7a504bf6
2016-12-20T23:18:56.692Z: workflow about to start
2016-12-20T23:18:56.748Z: replicating dockerstg.yourcompany.com/units/howdy-http-1.0:1.0.348 to remote registry docker.dc1.yourcompany.com/units
2016-12-20T23:19:03.648Z: 02960cb05ba7 Pulling fs layer
[...]
2016-12-20T23:19:03.661Z: 86c2e42552a1 Pull complete
[...]
2016-12-20T23:21:26.528Z: 428087232e7e Pushed
2016-12-20T23:21:26.529Z: writing alert definitions to texas's consul
2016-12-20T23:21:27.020Z: writing policy to vault: namespace=dev unit=howdy-http policy=dev__howdy-http datacenter=texas
2016-12-20T23:21:27.734Z: instructing dc1's service scheduler to handle service container
2016-12-20T23:21:28.518Z: ======> workflow completed <======
```

The logs indicate exactly what Nelson executed, and any internal errors that happened during deployment will also appear in the log.

### Runtime

Nelson can tell you about what it has logically done, and about its internal state. While this is most frequently useful, during debugging you might want to know the *actual* status of something in the runtime you asked Nelson to deploy your application too. In this case, the `runtime` command becomes useful, as it will fetch information from the scheduler and from consul as to the **current** state of your application.

```
λ ./bin/nelson stacks runtime f81c7a504bf6
==>> Stack Status
  STATUS:   ready
  EXPIRES:  2016-12-23T19:18:46Z

==>> Scheudler
  PENDING:    0
  RUNNING:    1
  COMPLETED:  0
  FAILED:     0

==>> Health Checks
  ID                                        NODE              STATUS   DETAILS
  c1443df51d7ed016f753357c56e917799ba66a7c  ip-10-113-130-88  passing  service: default howdy-http--1-0-348--qs1r9ost check
```

In the event you have multiple instances deployed (this example only has one), you will see all the relevant health checks listed here.

### Redeployment

There are occasions where you might want to try re-deploying a stack: for example, if your stack was idle for a long time and Nelson cleaned it up, as per the policy, or when the workflow had an error during deployment that was no fault of the user. These are both legit cases for re-deploying a stack, so the CLI supports it as a first-class stack operation. Redeployment will *cause a brand new deployment* of the same release version. The following is an example:

```
λ nelson stacks redeploy ec695f081fbd
==>> Redeployment requested for ec695f081fbd
```

<hr />

## Tips

Generally speaking the command line client is just like any other CLI tool, and it integrates perfectly well with other bash tools. What follows below is just a useful laundry list of bash commands working in concert with the *Nelson* CLI.

### Sorting Tables

It is possible to sort the resulting tables from the command line client by using the built-in `sort` command of bash:

```
λ nelson units list --namespaces dev --datacenters massachusetts | sort -k4
  GUID          NAMESPACE  UNIT              VERSION
  c0e4281e9c9d  dev      howdy-http        0.38
  44b17835fe41  dev      howdy-batch       0.38
  a0d3e7843856  dev      heydiddlyho-http  0.33
```

The `-k4` tells the `sort` command to order the output by the forth column. This works with arbitrary indexes, so you can sort by any column you want.

### Filtering Tables

Sometimes you might want to just find a specific line in a larger table (perhaps to see if something was terminated, for example). Whilst `grep` will work just fine, it is often useful to retain the headers for each column so you know what you're looking at. This can easily be achieved with `awk`:

```
λ nelson units list -ns dev -d massachusetts | awk 'NR==1 || /batch/'
  GUID          NAMESPACE  UNIT              VERSION
  44b17835fe41  dev      howdy-batch       0.38
```
