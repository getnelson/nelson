---
layout: "single"
toc: "false"
title: Configuration
preamble: >
  For installation instructions, see the [user guide](/getting-started/install.html) section. This reference guide covers the various configuration options Nelson can have in its configuration file and explains why you might want them.
contents:
- Overview
- Core
- Auditing
- Cleanup
- Database
- Datacenters
- Docker
- Email
- Github
- Network
- Pipeline
- Security
- Slack
- Templating
- User Interface
- Workflow Logger
menu:
  main:
    identifier: docs-configuration
    parent: docs
    url: /documentation/configuration.html
    weight: 5
---

## Overview

Nelson is configured using [Knobs](https://github.com/getnelson/knobs) which is a Scala port of the [Data.Configurator](http://hackage.haskell.org/package/configurator-0.3.0.0/docs/Data-Configurator.html) library from Haskell. This format is well-specified and using Knobs allows Nelson to give better, more accurate error messages in the event of a user configuration error. More practically, Knobs supports a simplistic syntax for common data types:

```
# strings
thing = "value"

# numbers
foo = 123

# durations
delay = 30 seconds
another = 1 minute

# lists of strings
list-of-thing = [ "buzz", "qux" ]

# list of integers
list-of-int = [ 80, 443 ]

```

In addition, Knobs allows for "grafting" which can make using the configuration file easier for operators. For example, Knobs supports grafting:

```
# overrides.cfg
nelson.network.discovery-delay = 3 minutes
```

Which can be "grafted" on a parent template:

```
nelson {
  ....
}
import overrides.cfg
```

This is frequently useful to assemble configuration from discrete modular parts. For a more exhaustive description of the configuration format itself, please see the [Knobs documentation](http://verizon.github.io/knobs/).

All configuration in Nelson is a sub-config of the `nelson` scope / namespace. This can either be specified using "block" sytanx, or "inline" syntax. Consider the following example:

```
# nested block syntax
nelson {
  timeout = 4 seconds
}

# which can also be written using inline syntax:
nelson.timeout = 4 seconds
```

These are 100% functionally equivalent, and there is no prescription on what style you use where in your deployment of Nelson. This guide uses inline syntax for referencing specific fields, simply for compactness and readability. It is however recommended that the block syntax be used in production deployments, as the Nelson team believe it is more readable for most users.

----

## Core

<span class="badge badge-warning">required</span>

Some configuration in Nelson is "top-level" in the `nelson` scope. These options are typically core to how Nelson operates, and Nelson administrators should carefully review these options to ensure that they are set to values that make sense for your deployment.

* [nelson.default-namespace](#nelson-default-namespace)
* [nelson.discovery-delay](#nelson-discovery-delay)
* [nelson.manifest-filename](#nelson-manifest-filename)
* [nelson.proxy-port-whitelist](#nelson-proxy-port-whitelist)
* [nelson.readiness-delay](#nelson-readiness-delay)
* [nelson.timeout](#nelson-timeout)

#### nelson.default-namespace

upon receiving a github release event, where should Nelson assume the
application get deployed. This can either be a root namespace,
or a subordinate namespace, e.g. `stage/unstable`... it's arbitrary, but the
namespace must exist (otherwise Nelson will attempt to create it on boot)

```
nelson.default-namespace = "dev"
```

#### nelson.discovery-delay

The frequency at which nelson should write out the discovery / routing protocol information to your configured routing sub-system. This should typically not be set too high as it will affect the frequency and choppiness of updates in the runtime. For example, if you set the value to 10 minutes and wanted to migrate traffic in 30 minutes, you'd end up with 3 large "steps" in that traffic-shifting curve (assuming a linear specification).

```
nelson.discovery-delay = 2 minutes
```

#### nelson.manifest-filename

When Nelson fetches the repository manifest, this field controls what filename Nelson should be looking for. By default, the expectation is that this config is a path from the root of the repository, but can be specified with relative paths (from the root of any given repository).

```
nelson.manifest-filename = ".nelson.yml"
```

#### nelson.proxy-port-whitelist

When using Nelson's [load balancer](/getting-started/routing.html#load-balancers) feature, to avoid exposing random ports on the internet, administrators can keep a whitelist of the ports they are permitting to be exposed. This helps mitigate issues with security surface areas, whilst not restricting users to some hardcoded defaults.

```
nelson.proxy-port-whitelist = [ 80, 443, 8080 ]
```

#### nelson.readiness-delay

How frequently should Nelson run the readiness check to see if a service deployment is ready to take active traffic. How frequently this needs to be will largely be affected by the routing sub-system you have elected to use. For example, queries to Consul, vs queries to Kubernetes.

```
nelson.readiness-delay = 3 minutes
```

#### nelson.timeout

Controls how long Nelson should wait when talking to external systems. For example, when Nelson makes a call to Vault, or a scheduling sub-system, how long should it wait?

```
nelson.timeout = 4 seconds
```

----

## Auditing

<span class="badge badge-info">optional</span>

Nelson has an internal auditing subsystem that keeps a track of all the events that happen, from deployments to cleanup and deliberate user actions.

* [audit.concurrency-limit](#audit-concurrency-limit)
* [audit.inbound-buffer-limit](#audit-inbound-buffer-limit)

#### audit.concurrency-limit

As Nelson is executing various parts of its system asynchronously, the audit system has to be able to respond to multiple inbound callers at the same time. This configuration option controls the size of the thread pool that is used specifically for auditing. This should typically be a lower number, but greater than `2`.

```
nelson.audit.concurrency-limit = 4
```

#### audit.inbound-buffer-limit

The auditing system operates as a buffered queue. In order to define the behavior of Nelson, the buffer is capped to a specific value controlled by the `inbound-buffer-limit` field. In practice this should not be a problem and the default should suffice in nearly every case, but be aware that if Nelson were internally queuing more than the value specified here the queue will block until items have been consumed by one of the auditing processor threads.

```
nelson.audit.inbound-buffer-limit = 50
```

----

## Cleanup

<span class="badge badge-info">optional</span>

The `cleanup` stanza controls how Nelson evaluates and executes the automatic cleanup operations on the service and job graphs.

* [cleanup.cleanup-delay](#cleanup-cleanup-delay)
* [cleanup.extend-deployment-time-to-live](#cleanup-cleanup-delay)
* [cleanup.initial-deployment-time-to-live](#cleanup-cleanup-delay)
* [cleanup.sweeper-delay](#cleanup-cleanup-delay)

#### cleanup.cleanup-delay

The cadence at which Nelson should process the stack topology and look for stacks that have been marked as garbage and are pending deletion. This timeout affects how quickly a stack moves from the `ready` state to the `garbage` state, and in turn how quickly items that were `garbage` actually get reaped.

```
nelson.cleanup.cleanup-delay = 10 minutes
```

#### cleanup.extend-deployment-time-to-live

When Nelson determines that a stack is still useful and is to avoid deletion, how long should that stack TTL be increased by? This parameter should be set to the longest time that you would be prepared to wait for a stack to be destroyed. Be aware that if the TTL is less than the `cleanup-delay` parameter then Nelson will find your stacks to be garbage and delete them. Change this parameter with caution and be sure to validate the behavior is what you want.

```
nelson.cleanup.extend-deployment-time-to-live = 30 minutes
```

#### cleanup.initial-deployment-time-to-live

When a stack is first launched by Nelson, how much of a grace period should be given before Nelson starts to subject the stack to the typical garbage collection process? This is the maximum time that Nelson will check for readiness - once the period elapses, if the stack is not in the `ready` state it will fall subject to the garbage collection process.

```
nelson.cleanup.initial-deployment-time-to-live = 30 minutes
```

#### cleanup.sweeper-delay

Nelson is publishing a set of metadata about stacks to the configured routing subsystem of your choice. Cleaning the discovery / routing systems up is typically done on a slower cadence in the event that a stack needed to be redeployed or an error occurred and something needed to be recovered. The longer this period is set too, the more cruft will accumulate in your discovery / routing system (for example, Consul's KV storage).

```
nelson.cleanup.sweeper-delay = 24 hours
```

----

## Database

<span class="badge badge-warning">required</span>

Nelson's H2 database requires some configuration to use. By default H2 will just write the database into the process-local folder, but this is typically not what you want. Most operators prefer the Nelson database to be on a redundant or backed-up known location.

* [database.driver](#database-driver)
* [database.connection](#database-connection)
* [database.username](#database-username)
* [database.password](#database-password)

#### database.driver

At the time of writing, Nelson only supported H2 as its backend datastore; this field should typically not be changed by administrators.

```
nelson.database.driver = "org.h2.Driver"
```

#### database.connection

Depending on the driver specified in the `nelson.database.url` field, configure an appropriate JDBC string:

```
nelson.database.connection = "jdbc:h2:file:/opt/application/db/nelson;DATABASE_TO_UPPER=FALSE;AUTO_SERVER=TRUE;"
```

#### database.username

If your database is using authentication, specify the username with the `username` field:

```
nelson.database.username = "admin"
```

#### database.password

If your database is using authentication, specify the password with the `password` field:

```
nelson.database.password = "some password"
```

----

## Datacenters

<span class="badge badge-warning">required</span>

The datacenters configuration section is what controls the various places that Nelson can deploy to. Within this section you can configure how Nelson knows how to talk to your scheduler of choice, instruct Nelson where it's Vault instance is, configure dynamic PKI and so forth. The structure of this section is namespaced by the datacenter in question; consider the following example:

```
datacenters {
  texas {
    ...
  }
  portland {
    ...
  }
}
```

In this case two datacenters are configured: `texas` and `portland` - be aware that the names here are arbitrary, but once you choose them it is highly recommended to avoid changing them in the future as end-users can target specific datacenters for data-gravity or placement reason using the [datacenter stanza](/documentation/manifest.html#datacenters) of the Nelson manifest file, so future changes can be potentially disruptive. It is also highly advisable that you choose unambiguous naming. For example, regions in [AWS](https://aws.amazon.com/) are things like <br/> `us-west-1` and `us-west-2` whilst region names in [GCP](https://cloud.google.com/) are `us-west1`; using these very similar names verbatim can be exceedingly confusing for users. Most companies internally have some taxonomy for their datacenter naming, so using something that users can identify with is usually advisable. Some Nelson users like to use regional airport codes, or even internal codewords - whatever scheme you choose, ensure users understand it.

The following sub-sections will assume that the datacenter name is identified by `*`, representing whatever the administrator configured.

* [datacenters.*.docker-registry](#datacenters-docker-registry)
* [datacenters.*.domain](#datacenters-domain)
* [datacenters.*.infrastructure.consul](#datacenters-infrastructure-consul)
* [datacenters.*.infrastructure.kubernetes](#datacenters-infrastructure-kubernetes)
* [datacenters.*.infrastructure.loadbalancer](#datacenters-infrastructure-loadbalancer)
* [datacenters.*.infrastructure.nomad](#datacenters-infrastructure-nomad)
* [datacenters.*.infrastructure.vault](#datacenters-infrastructure-vault)
* [datacenters.*.policy](#datacenters-policy)

#### datacenters.*.domain

Every datacenter typically has a "root domain", used for DNS purposes. How this DNS is hosted does not really matter, but examples might be something like `texas.dc.yourcompany.com` or `us-east-1.aws.company.net` etc. This will be used by Nelson when publishing routing information for a given datacenter.

```
datacenters.texas.domain = "texas.dc.yourcompany.com"
```

#### datacenters.*.docker-registry

Defines the URL for your docker registry within a given datacenter. Be aware that some operators choose to have a large central registry, which all regions pull from. In this case you can specify the same URL for multiple datacenters, but be aware that this will damage the overall reliability of your system in the event that registry is unavailable. This is why Nelson encourages deployments to have a registry per-datacenter.

```
datacenters.texas.domain = "reg.texas.dc.yourcompany.com"
```

----

### datacenters.*.infrastructure

<span class="badge badge-warning">required</span>

The `infrastructrue` section of a given datacenter stanza is one of the most comprehensive sections of the configuration, and as such has been broken down into a handful of subordinate sections in this guide.

* [datacenters.*.infrastructure.scheduler](#datacenters-infrastructure-scheduler)
* [datacenters.*.infrastructure.routing](#datacenters-infrastructure-routing)

#### datacenters.*.infrastructure.scheduler

As Nelson is integrated with a variety of scheduling systems, the administrator has to instruct Nelson which scheduling substrate is to be used in a given datacenter.

```
# to use the nomad integration
infrastructure.scheduler = "nomad"

# to use the kubernetes integration
infrastructure.scheduler = "kubernetes"
```

This section *must* be specified otherwise Nelson will fault at startup and complain that there are no schedulers specified.

#### datacenters.*.infrastructure.routing

Routing is a sophisticated subsystem within Nelson that defines where and how routing and readiness information is calculated and delivered.

```
# use the kubernetes readiness probes as indication of health
infrastructure.scheduler = "kubernetes"

# use the consul health checks as indication of readiness and health
# whilst publishing the routing data in the lighthouse format to the
# consul key-value storage system
infrastructure.scheduler = "consul:lighthouse"

# or if you prefer to be less explicit:
infrastructure.scheduler = "consul"

# ignore routing system and do not publish any information and assume
# that all stacks become healthy right away.
infrastructure.scheduler = "noop"
```

This section *must* be specified otherwise Nelson will fault at startup.

----

### datacenters.*.infrastructure.consul

<span class="badge badge-info">optional</span>

This section controls how Nelson interacts with your Consul cluster.

#### consul.endpoint

Specify the URL that Nelson can reach your Consul servers at. It is highly recomended that this communication take place over HTTPS, and the certificate in use matches the domain being used on the remote server. If you can successfully cURL from the Nelson machine to the endpoint, then Nelson should be able to interact with Consul without issue.

```
consul.endpoint  = "http://consul.texas.your.company.com"
```

#### consul.timeout

Controls how long Nelson should wait for timeouts when interacting with Nomad. This configuration parameter is useful to tune if your Nelson is interacting with a remote Consul cluster in a regional datacenter (i.e. datacenters further away from Nelson will require a longer timeout because of the networking latency). If tuning this parameter, monitoring the Nelson logs and metrics carefully to ensure you do not synthetically introduce high failure rates.

```
consul.timeout = 1 second
```

#### consul.acl-token

Consul has a [sohpisticated ACL system](https://www.consul.io/docs/guides/acl.html) that is typically used in production deployments. In order for Nelson to communicate with Consul in such production environments, it requires an ACL token which allows it to read from the service catalog, write to the key-value store etc.

```
consul.acl-token = "XXXXXXXXX"
```

Note that as of Consul 1.4+, a new ACL system was introduced to Consul and the token provided to Nelson should have an ACL policiy that uses something like the following:

```
service_prefix "" {
  policy = "write"
}
node_prefix "" {
  policy = "read"
}
key_prefix "" {
  policy = "write"
}
event_prefix "" {
  policy = "read"
}
key_prefix "_rexec" {
  policy = "write"
}
```

Depending upon your configuration - both of Consul and of Nelson - the above policy could be restricted further to reduce the security profile, but this example definition should provide an indicitive configuration as a starting point.

#### consul.username

While not specifically required by Consul, it is common for operators to put a basic-auth proxy in front of their Consul UI, which can impede Nelon's ability to access the Consul API. If this is the case, specify the `username` here:

```
consul.username  = "XXXXXXXXX"
```

#### consul.password

While not specifically required by Consul, it is common for operators to put a basic-auth proxy in front of their Consul UI, which can impede Nelon's ability to access the Consul API. If this is the case, specify the `password` here:

```
consul.password  = "XXXXXXXXX"
```


----

### datacenters.*.infrastructure.kubernetes

<span class="badge badge-info">optional</span>

Nelson has an integration with Kubernetes, but the setup is a little different to Nelson's other scheudler implementations. As Kubernetes utilizes a so-called "fat-client" called `kubectl`, Kubernetes API server does not make direct integrations easy, as so much logic is embedded in the client itself. With this frame, Nelson actually shells out (i.e. fork-exec) to the `kubectl` command-line client in order to interact with the Kubernetes cluster in question.

In addition, some operators even decide to run Nelson itself on top of the Kubernetes cluster that Nelson is controlling. If this is a good idea or not is out of scope for this guide, but it is a supported operational use case.

* [kubernetes.in-cluster](#kubernetes-in-cluster)
* [kubernetes.kubeconfig](#kubernetes-kubeconfig)
* [kubernetes.timeout](#kubernetes-timeout)

#### kubernetes.in-cluster

Specifies if the Nelson server itself is running inside a Kubernetes cluster or not. This parameter determintes if Nelson will attempt to use the `kubeconfig` specified by `kubernetes.kubeconfig` field, or if it will use the implicitly defined one inside the cluster, and authenticate using the [ServiceAccount](https://kubernetes.io/docs/tasks/configure-pod-container/configure-service-account/) that Nelson was launched with.

```
kubernetes.in-cluster = false
```

#### kubernetes.kubeconfig

If *not* running inside a Kubernetes cluster, but instead interacting with one remotely, then it is nessicary to configure a [kubeconfig](https://kubernetes.io/docs/tasks/access-application-cluster/configure-access-multiple-clusters/#create-a-second-configuration-file) file that has the needed information to access your cluster.

Note that this design allows users to continue to use whatever Kubernetes authentication scheme they are using, without any specific integrations being needed inside Nelson.

```
kubernetes.kubeconfig = "/opt/application/conf/kubeconfig"
```

#### kubernetes.timeout

Controls how long Nelson should wait for timeouts when interacting with Kubernetes. This configuration parameter is useful to tune if your Nelson is interacting with a remote cluster in a regional datacenter (i.e. datacenters further away from Nelson will require a longer timeout because of the networking latency). If tuning this parameter, monitoring the Nelson logs and metrics carefully to ensure you do not synthetically introduce high failure rates.

```
kubernetes.timeout    = 3 seconds
```


----

### datacenters.*.infrastructure.nomad

<span class="badge badge-info">optional</span>

This section controls how Nelson interacts with [Nomad](https://www.nomadproject.io/). If you are not using the Nomad scheduler in your datacenter, then you can omit this section. When launching tasks onto your Nomad installation, Nelson will use the API and requires no external binaries.

* [nomad.endpoint](#nomad-endpoint)
* [nomad.timeout](#nomad-timeout)

#### nomad.endpoint

Specify the URL that Nelson can reach your Nomad servers at. It is highly recomended that this communication take place over HTTPS, and the certificate in use matches the domain being used on the remote server. If you can sucsessfully cURL from the Nelson machine to the endpoint, then Nelson should be able to interact with Nomad without issue.

As Nomad servers are often operated as a cluster with a dynamically selected leader, it is common for operators to front their Nomad cluster with a load-balancer in order to reach *any* Nomad server. This is generally fine and Nelson will work perfectly well when Nomad is in this configuration.

```
nomad.endpoint   = "https://nomad.texas.company.corp:1234"
```

#### nomad.timeout

Controls how long Nelson should wait for timeouts when interacting with Nomad. This configuration parameter is useful to tune if your Nelson is interacting with a remote Nomad in a regional datacenter (i.e. datacenters further away from Nelson will require a longer timeout because of the networking latency). If tuning this parameter, monitoring the Nelson logs and metrics carefully to ensure you do not synthetically introduce high failure rates.

```
nomad.timeout = 500 milliseconds
```


----

### datacenters.*.infrastructure.vault

<span class="badge badge-warning">required</span>

This section controls how Nelson should interact with the Vault configured in this datacenter. At the time of writing, Vault is a hard requirement and must be configured.

* [vault.endpoint](#vault-endpoint)
* [vault.auth-token](#vault-auth-token)
* [vault.timeout](#vault-timeout)
* [vault.auth-role-prefix](#vault-auth-role-prefix)

#### vault.endpoint

Specify the URL that Nelson can reach your Vault server at. It is highly recomended that this communication take place over HTTPS, and the certificate in use matches the domain being used on the remote server. If you can sucsessfully cURL from the Nelson machine to the Vault endpoint, then Nelson should be able to interact with Vault without issue.

```
vault.endpoint   = "https://vault.texas.company.corp:1234"
```

#### vault.auth-token

As Nelson is *generating* Vault policies, it requires a fairly privilidged authentication token, which should be specified as follows:

```
vault.auth-token = "XXXXXXXXX"
```

The token that is used here should typically have the ability to create, list and delete vault policies, along with creating [backend auth roles](https://www.vaultproject.io/docs/auth/) (if your scheduling setup needs that functionality). For more information on Vault policies, please [read the Vault documentation](https://www.vaultproject.io/docs/concepts/policies.html).

#### vault.timeout

Controls how long Nelson should wait for timeouts when interacting with Vault. This configuration parameter is useful to tune if your Nelson is interacting with a remote Vault in a regional datacenter (i.e. datacenters further away from Nelson will require a longer timeout because of the networking latency). If tuning this parameter, monitoring the Nelson logs and metrics carefully to ensure you do not synthetically introduce high failure rates.

```
vault.timeout = 500 milliseconds
```

#### vault.auth-role-prefix

If your backend auth provider (for kubernetes, as one example) has the same name as the datacenter, you can ignore this field. Given this is not typically the case (operators typically have some convention) then you can specify a prefix that will be prepended to the datacenter name.

```
vault.auth-role-prefix = "aws-"
```

This configuration parameter is typically not used. Feel free to drop into the [community channels](/documentation/contributing.html) and talk about your use case if you think you need to use it.

----

### datacenters.*.infrastructure.loadbalancer

<span class="badge badge-info">optional</span>

In order to enable Nelson's support for dynamically provisioning load-balencers in public cloud, Nelson needs to know the networking configuration of the target environment. Presntly, only [AWS](https://aws.amazon.com/) is supported.

* [loadbalancer.aws.access-key-id](#loadbalancer-aws-access-key-id)
* [loadbalancer.aws.availability-zones](#loadbalancer-aws-image)
* [loadbalancer.aws.elb-security-group-names](#loadbalancer-aws-image)
* [loadbalancer.aws.image](#loadbalancer-aws-image)
* [loadbalancer.aws.launch-configuration-name](#loadbalancer-aws-launch-configuration-name)
* [loadbalancer.aws.region](#loadbalancer-aws-region)
* [loadbalancer.aws.secret-access-key](#loadbalancer-aws-secret-access-key)
* [loadbalancer.aws.use-internal-elb](#loadbalancer-use-internal-elb)

#### loadbalancer.aws.access-key-id

To provide Nelson access to AWS to launch the needed resources, provide an AWS access-key-id and secret-access-key. If Nelson itself is running on an AWS EC2 instance, then it is highly recomended to not specify credentials statically, but instead rely on the IAM instance profile which Nelson will automatically read and use if the static configuration fields are missing.

```
# prefer IAM profiles if using AWS EC2 to host Nelson itself
datacenter.texas.infrastructure.loadbalancer.aws.access-key-id = "XXXXXXXXX"
```

#### loadbalancer.aws.availability-zones

In order to launch load-balancer and auto-scaling group resources, Nelson needs to know what network subnets in your VPC should be used. Nelson makes the assumption that your network has "public" and "private" subdivisions, but if for some reason this is not the case or you do not want Nelson to ever expose publically addressable resources then simply specify the internal subnets in both `private-subnet` and `public-subnet` fields.

```
availability-zones {
  texas-a {
    private-subnet = "subnet-AAAAAAAA"
    public-subnet = "subnet-BBBBBBB"
  }
  texas-b {
    private-subnet = "subnet-QQQQQQ"
    public-subnet = "subnet-XXXXXX"
  }
}
```

#### loadbalancer.aws.elb-security-group-names

When Nelson launches the load-balancer you can configure the security group that this ELB should be placed in, which allows operators to decide exactly what firewall rules should be in place. Ultimately this allows administrators to ensure that only specified resources get made public.

```
datacenter.texas.infrastructure.loadbalancer.aws.elb-security-group-names = [ "sg-AAAAAAAA" ]
```

#### loadbalancer.aws.image

Some operators prefer to run the proxy software as a container that is dynamically injected by Nelson. Others prefer to run baked AMIs that are specified in the launch config. If you want to use a container as the packaging mechinism for the inbound proxy, then specify it here, otherwise omit this field.

```
datacenter.texas.infrastructure.loadbalancer.aws.image = "registry.texas.corp/whatever/infra-lb:1.2.3"
```

#### loadbalancer.aws.launch-configuration-name

Nelson will use the specified launch configuration to launch the resources. How this launch configuration is specified - perhaps CloudFormation or Terraform - is out of scope for this documentation, as Nelson only needs to know the name of the launch configuration to boot as a "black box".

```
datacenter.texas.infrastructure.loadbalancer.aws.launch-configuration-name = "yourlb-XXXXXXXXXXXX"
```

#### loadbalancer.aws.region

What region should Nelson use when launching AWS resources for this datacenter. This is needed as no assumptions are made that datacenter names fully map to AWS region conventions.

```
datacenter.texas.infrastructure.loadbalancer.aws.region = "us-west-2"
```

#### loadbalancer.aws.secret-access-key

To provide Nelson access to AWS to launch the needed resources, provide an AWS access-key-id and secret-access-key. If Nelson itself is running on an AWS EC2 instance, then it is highly recomended to not specify credentials statically, but instead rely on the IAM instance profile which Nelson will automatically read and use if the static configuration fields are missing.

```
datacenter.texas.infrastructure.loadbalancer.aws.secret-access-key = "XXXXXXXXX"
```

#### loadbalancer.aws.use-internal-elb

Depending on your network topology, it can sometimes be desirable to have Nelson spawn internal ELBs, which are not exposed to the internet (and cannot be exposed). In this case, Nelson assumes that you have some kind of VPN or other network route to reach these ELBs.

```
datacenter.texas.infrastructure.loadbalancer.aws.use-internal-elb = true
```

----

### datacenter.*.policy

<span class="badge badge-info">optional</span>

The `policy` section informs Nelson how Vault policies should be generated, and if additional paths should be added to generated policies.

* [policy.resource-creds-path](#policy-resource-creds-path)
* [policy.pki-path](#policy-pki-path)

#### policy.resource-creds-path

When Nelson generates policies for credentials in Vault, it generates the path based on the template specified with `resource-creds-path`. Units will get read capability on each resource specified. For more information on administering Vault with Nelson, please [see the operator guide](/documentation/operator.html#administering-vault).

Supported variables are as follows:

* `%env%` - root namespace being used for this particular deployment, for example: `dev`.
* `%resource%` - value will be defined by the `resources` section of the manifest file.
* `%unit%` - the unit name of the application being deployed.

For more information on the Nelson terminology, please see [the getting started guide](/getting-started/)

```
datacenters.texas.policy.resource-creds-path = "yourcompany/%env%/%resource%/creds/%unit%"
```

#### policy.pki-path

It is often desirable to have Vault generate dynamic certificates for every container within a stack deployment. However, to do this, the Vault policy being used by the stack in question needs to be specially configured in order to leverage the PKI backend within Vault. With this frame, the `pki-path` explicitly configures how the PKI backend will be referenced in the generated Vault policy.

Supported variables are as follows:

* `%env%` - root namespace being used for this particular deployment, for example: `dev`.

```
datacenters.texas.policy.pki-path = "pki/%env%"
```

----

## Docker

<span class="badge badge-warning">required</span>

For some workflows and developer experience functionality, Nelson requires access to a [Docker](https://docker.com) daemon to launch and replicate containers. In order to do this, Nelson must be told how to talk to the Docker process.

* [docker.connection](#docker-connection)
* [docker.verify-tls](#docker-verify-tls)

#### docker.connection

Docker supports a range of ways to connect to the daemon process and any of the Docker-supported URIs are valid for this field.

```
# using tcp (recomended for security reasons)
nelson.docker.connection = "tcp://0.0.0.0:2376"

# using unix sockets
nelson.docker.connection = "unix:///path/to/docker.sock"
```

#### docker.verify-tls

Depending on your Docker process configuration, you may want to skip TLS verification. The default is to verify TLS (as that is the recommended, secure configuration), but you can optionally disable that verification here.

```
nelson.docker.verify-tls = true
```

----

## Email

<span class="badge badge-info">optional</span>

Nelson can notify you by email when changes to deployments happen. In order to do this, Nelson needs to be configured with an SMTP server. This is fully compatible with public cloud email offerings like SES (or any other provider that implements the SMTP protocol).

* [email.host](#email-host)
* [email.port](#email-port)
* [email.from](#email-from)
* [email.user](#email-user)
* [email.password](#email-password)

#### email.host

Controls where Nelson will look for your SMTP email server.

```
nelson.email.host = "mail.company.com"
```

#### email.port

What port should Nelson use when talking to your SMTP email server.

```
nelson.email.port = 9000
```

#### email.from

When Nelson sends emails about system status, what should the `From` line in the email be?

```
nelson.email.from = "nelson@example.com"
```

#### email.user

If your SMTP server requires authentication, what username should be used.

```
nelson.email.user = "someuser"
```

#### email.password

If your SMTP server requires authentication, what password should be used.

```
nelson.email.password = "somepassword"
```

----

## Github

<span class="badge badge-warning">required</span>

Nelson requires a set of Github credentials in order to be able to interact with your source code. For more information, please [see the installation section](/getting-started/install.html#nelson-server).

* [github.client-id](#github-client-id)
* [github.client-secret](#github-client-secret)
* [github.github.scope](#github-github.scope)
* [github.access-token](#github-access-token)
* [github.system-username](#github-system-username)


#### github.client-id

```
nelson.github.client-id = "xxxxxxxxxxx"
```

#### github.client-secret

```
nelson.github.client-secret = "yyyyy"
```

#### github.redirect-uri

What is the fully qualified URL that Github will redirect the OAuth exchange back too. This should match the domain configured in `network.external-host` and `network.external-port`, if configured.

```
nelson.github.redirect-uri = "https://nelson.local/auth/exchange"
```

#### github.scope

What OAuth scopes should Nelson use when interacting with Github. Read more about Github OAuth scopes [on the Github documentation site](https://developer.github.com/apps/building-oauth-apps/understanding-scopes-for-oauth-apps/).

```
nelson.github.scope  = "repo"
```

#### github.system-username

Configure the Github username that Nelson will conduct operations as. This is needed because some operations that Nelson does are not in the context of any human interaction. For example, when Nelson conducts a deployment, it's all happening automatically and asynchronously from any human, and thus human-user OAuth tokens cannot be utilized within these operations. To protect privacy Nelson does not store end-user OAuth tokens, and as such needs its own user to interact with your Github account.

```
nelson.github.system-username = "nelson"
```

#### github.access-token

Configure an access token for the account specified by `system-username`.

```
nelson.github.access-token = "replaceme"
```

----

## Network

<span class="badge badge-warning">required</span>

Specify the networking options used by Nelson, including the network interface to which the JVM binds, port binding, and the address Nelson advertises in its API.

* [network.bind-host](#network-bind-host)
* [network.bind-port](#network-bind-port)
* [network.external-host](#network-external-host)
* [network.external-port](#network-external-port)
* [network.idle-timeout](#network-idle-timeout)

#### network.bind-host

What host - interface - should Nelson bind its JVM server process too.

```
nelson.network.bind-host = "0.0.0.0"
```

#### network.bind-port

What TCP port should Nelson bind its JVM server process too. This is typically a non-privileged port (i.e. above port 3000).

```
nelson.network.bind-port = "9000"
```

#### network.external-host

Typically Nelson is hosted behind a reverse proxy, or otherwise bound to an internal address. In order for cookie retention to work for browsers, and for Nelson to rewrite its generated URLs to locations that are network accessible, the operator must tell Nelson what that external address is.

```
nelson.network.external-host = "nelson.yourco.com"
```

Whilst not recommended, you can also use an IP address here if you do not have an internal domain name server.

```
nelson.network.external-host = "10.10.1.11"
```

#### network.external-port

If using a reverse proxy, and `external-host` is set, what port should be used for external responses (i.e. HTTP 302 redirects and so forth). If external-port is not supplied, then we assume port 443 (https)

```
nelson.network.external-port = 443
```

#### network.idle-timeout

`idle-timeout` defines how long should nelson wait before closing requests to the Nelson server process. Under the hood, this equates to `idle-timeout` on http4s' [BlazeBuilder](https://github.com/http4s/http4s/blob/master/blaze-server/src/main/scala/org/http4s/server/blaze/BlazeBuilder.scala))

```
nelson.network.idle-timeout = 60 seconds
```

#### network.monitoring-port

Nelson uses [Prometheus](https://prometheus.io/) as its monitoring solution. Prometheus exposes its metrics for collection via a TCP port, and the `monitoring-port` allows you to configure what port that is.

```
nelson.network.monitoring-port = 5775
```

----

## Pipeline

<span class="badge badge-info">optional</span>

Nelson's internal "pipeline" is the primary queue and dequeuing mechanism from which the whole deployment process is executed. In short, the pipeline is a work-stealing dequeue, and the configuration allow you to tune the parallelism of this queue and so forth.

* [pipeline.concurrency-limit](#pipeline-concurrency-limit)
* [pipeline.inbound-buffer-limit](#pipeline-inbound-buffer-limit)

#### pipeline.concurrency-limit

Defines maximum number of pipeline workflows Nelson will handle at the same time. This limit is typically bound by the hardware that the Nelson server is running on, but a general rule of thumb is to set this value to the same number as the total count of processors that are available to on the machine (or available to Nelson if running in a container).

```
nelson.pipeline.concurrency-limit = 4
```

#### pipeline.inbound-buffer-limit

Internally, the pipeline is implemented as a queue, and all queues buffer. To avoid queuing infinitely and potentally running out of memory it is prudent to set a maximum size for the queue - most everyone should be using the default value here, and changing this should be very rare as if you have more that 50 pending deployments, something is very wrong (on the basis that Nelson typically processes an entire workflow in a few seconds).

```
nelson.pipeline.inbound-buffer-limit = 50
```

----

## Security

<span class="badge badge-warning">required</span>

Nelson has a variety of security options available to administrators. For the most part these are set-once variables, but they affect the operation of the system and can impact users, so it's important to understand their purpose.

* [security.encryption-key](#security-encryption-key)
* [security.expire-login-after](#security-expire-login-after)
* [security.key-id](#security-key-id)
* [security.signature-key](#security-signature-key)
* [security.use-environment-session](#security-use-environment-session)

#### security.encryption-key

As the name suggests, this is the encryption key that Nelson uses for its session tokens, supplied and passed around by users of the Nelson API. This key is typically `base64` encoded and 24 characters long. To make generation of these encryption keys easy, we have [provided a script](https://github.com/getnelson/nelson/blob/master/bin/generate-keys) which will dynamically produce a new candidate for a key. Keep in mind that whilst these tokens need to be encrypted, the level of encryption here only needs to be good enough to protect the contents for a period defined by `expire-login-after`.

```
# this is a dummy value for example purposes only;
# !!!!!! DO NOT USE THIS VALUE !!!!!!!
nelson.security.encryption-key = "B1oy5R9nVAw1gv0zAlreRg=="
```

#### security.expire-login-after

This field affects the cadence at which user tokens will be refreshed. To be specific, this means that the user of the Nelson CLI will, upon issuance, be able to use that token up to the maximum bound defined by this field (excluding a change in the encryption or signing keys). This value should be set to a size that is not too large, but also not too small causing users to constantly be refreshing tokens which might unduly burden the system if you have a lot of concurrent users.

```
# as the field is a duration, you can specify it with
# a few different formats:
nelson.security.expire-login-after = 7 days

# using hours, minutes or seconds etc
nelson.security.expire-login-after = 48 hours
```

#### security.key-id

The key-id is an implementation detail, and is usually statically set by an operator and never changed. Earlier revisions of Nelson used a rotating key system, which was never open-sourced, and so the key-id is a vestigial configuration from that historical detail.

```
nelson.security.key-id = 468513861
```

#### security.signature-key

The signature key is used to sign tokens to ensure they were not tampered with by clients. Every token will be signed with this key, so keep it safe just like you would the `encryption-key`.

```
# this is a dummy value for example purposes only;
# !!!!!! DO NOT USE THIS VALUE !!!!!!!
nelson.security.signature-key = "1toKbHQzLfb+zLeONuFdIA=="
```

#### security.use-environment-session

For development purposes, sometimes we want to not have to actually log in
with Github OAuth because we're developing locally (potentially even offline). When set to `true` this configuration forces the system to "auto-auth" based on local environment variables from the shell in which Nelson was started. This really is for development use only and should *never*, *ever*, be used in production.

```
nelson.security.use-environment-session = false
```

----

## Slack

<span class="badge badge-info">optional</span>

Nelson can integrate with Slack and send notifications to a Slack channel of your choice.

* [slack.webhook-url](#slack-webhook-url)
* [slack.username](#slack-username)

#### slack.webhook-url

The url that your Slack administrator provides from the Slack webhook configuration. For more information please see the [Slack webhook setup guide](https://api.slack.com/incoming-webhooks).

```
nelson.slack.webhook-url = "https://hooks.slack.com/services/....."
```

#### slack.username

Defines the name that will appear as the poster of messages from Nelson in the Slack channel.

```
nelson.slack.username = "Nelson"
```

----

## Templating

<span class="badge badge-info">optional</span>

In order to lint templates, you need to configure the templating engine. This configuration section is primarily exposing the limits and options which control how Nelson will spawn an ephemeral container to lint the template supplied by the user.

* [template.cpu-period](#template-cpu-period)
* [template.cpu-quota](#template-cpu-quota)
* [template.memory-mb](#template-memory-mb)
* [template.timeout](#template-timeout)


#### template.cpu-period

Specify the [CPU CFS](https://en.wikipedia.org/wiki/Completely_Fair_Scheduler) scheduler period, which is used alongside `cpu-quota`. defaults to 100 micro-seconds.

```
nelson.template.cpu-period = 100000
```

#### template.cpu-quota

Impose a [CPU CFS](https://en.wikipedia.org/wiki/Completely_Fair_Scheduler) quota on the container. The number of microseconds per cpu-period that the container is limited to before throttled; acting as the effective ceiling for the execution.

```
nelson.template.cpu-quota = 50000
```

#### template.memory-mb

Control the maximum amount of memory assigned to this template container execution. Typically this should be a small value, and most templating engines only use a very small amount of memory.

```
nelson.template.memory-mb = 8
```

#### template.timeout

When Nelson spawns a child container, configure the maximum duration to give the container before it is actively killed. This is set to ensure that the Nelson server does not end up with zombie containers which end up suffocating the host system.

```
nelson.template.timeout = 10 seconds
```

#### template.temp-dir

What host-path should Nelson bind into the temporary container as a scratch space. Whatever templating engine you've selected, the output template will be written to this scratch space prior to being deleted.

```
nelson.template.temp-dir = "/tmp"
```

#### template.template-engine-image

The Nelson ecosystem has [a set of containers](https://github.com/getnelson/containers) that are available to be used as templating linting engines. Typically the administrator chooses a template engine for the organization and this is used throughout - all template linting is assumed to be of the same form and users cannot dynamically mix and match. This container image must be accessible (i.e. pull'able by the Docker daemon on the Nelson host).

```
nelson.template.template-engine-image = "getnelson/linter-consul-template:2.0.13"
```

----


## User Interface

<span class="badge badge-info">optional</span>

Nelson has a pluggable user-interface and can be pointed at a location on disk to provide a custom UI, or the UI can be disabled entirely.

* [ui.enabled](#ui-enabled)
* [ui.file-path](#ui-file-path)

#### ui.enabled

Should nelson serve a user interface, or not?

```
nelson.ui.enabled = true
```

#### ui.file-path

Where are the assets located on disk, that Nelson will serve as its UI contents; this is for local development purposes only and is not typically used in production, as the UI is typically bundled with Nelson itself. However, if desired, this configuration can be used to provide a custom or otherwise altered UI from local disk.

```
nelson.ui.file-path = "/path/to/nelson/ui"
```

----


## Workflow Logger

<span class="badge badge-info">optional</span>

* [workflow-loggger.inbound-buffer-limit](#workflow-loggger-inbound-buffer-limit)
* [workflow-loggger.file-path](#workflow-loggger-file-path)


#### workflow-logger.inbound-buffer-limit

Every workflow process logs progress information to a file on disk. The logging is run as a separate, asynchronous background processes where writes are first put into a queue and then serialized into the specific file. This configuration value controls what the maximum size of that queue buffer is - most implementations will never need to change the default value.

```
nelson.workflow-logger.inbound-buffer-limit = 50
```

#### workflow-logger.file-path

The base directory where the workflow logging files are stored on disk. Ensure when deploying Nelson as a container that this location is bind-mounted to a host volume, or uses a docker-volume container, otherwise the files that were journaled to disk will be destroyed when the container quits.

```
nelson.workflow-logger.file-path = "/var/nelson/log"
```
