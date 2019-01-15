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

Nelson is configuring using [Knobs](https://github.com/getnelson/knobs) which is a Scala port of the [Data.Configurator](http://hackage.haskell.org/package/configurator-0.3.0.0/docs/Data-Configurator.html) from Haskell. This format is well-specified and using Knobs allows Nelson to give better, more accurate error messages in the event of a user configuration error. More practically, Knobs supports a simplistic syntax for common data types:

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

This is frequently useful to assembly configuration from discrete modular parts. For a more exhasutive description of the configuration format itself, please see the [Knobs documentation](http://verizon.github.io/knobs/).

All configuration in Nelson is a sub-config of the `nelson` scope / namespace. This can either be specified using "block" sytanx, or "inline" syntax. Consider the following example:

```
# nested block syntax
nelson {
  timeout = 4 seconds
}

# which can also be written using inline syntax:
nelson.timeout = 4 seconds
```

These are 100% functionally equivalent, and there is no prescription on what style you use where in your deployment of Nelson. The rest of this guide will use block-syntax as the Nelson team believe it is more readable for most users.

## Core

Some configuration in Nelson is "top-level" in the `nelson` scope. These options are typically core to how Nelson operates, and Nelson administrators should carefully review these options to ensure that they are set to values that make sense for your deployment.

* [nelson.default-namespace](#nelson-default-namespace)
* [nelson.discovery-delay](#nelson-discovery-delay)
* [nelson.manifest-filename](#nelson-manifest-filename)
* [nelson.proxy-port-whitelist](#nelson-proxy-port-whitelist)
* [nelson.readiness-delay](#nelson-readiness-delay)
* [nelson.timeout](#nelson-timeout)

#### nelson.default-namespace 

upon receiving a github release event, where should Nelson assume the
application should get deployed too. This can either be a root namespace,
or a subordinate namespace, e.g. `stage/unstable`... its arbitrary, but the
namespace must exist (otherwise Nelson will attempt to create it on boot)

```
nelson.default-namespace = "dev"
```

#### nelson.discovery-delay

The frequency on which should nelson write out the discovery / routing protocol information to your configured routing sub-system. This should typically not be set too high as it will affect the frequency and choppiness of updates in the runtime. For example, if you set the value to 10 minutes and wanted to migrate traffic in 30 minutes, you'd end up with 3 large "steps" in that traffic-shifting curve (assuming a linear specification). 

```
nelson.discovery-delay = 2 minutes
```

#### nelson.manifest-filename

When Nelson fetches the repository manifest, this field controls what filename Nelson should be looking for. By default, the expectation is that this config is a path from the root of the repository, but can be specified with relative paths (from the root of any given repository).

```
nelson.manifest-filename = ".nelson.yml"
```

#### nelson.proxy-port-whitelist

When using Nelson [load balancer](/getting-started/routing.html#load-balancers) feature, to avoid exposing random ports on the internet, administrators can keep a whitelist of the ports they are permitting to be exposed. This helps mitigate issues with security surface areas, whilst not restricting users to some hardcoded defaults.  

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

## Auditing

Nelson has an internal auditing subsystem that keeps a track of all the events that happen; from deployments to cleanup and deliberate user actions.

* [audit.concurrency-limit](#audit-concurrency-limit)
* [audit.inbound-buffer-limit](#audit-inbound-buffer-limit)

#### audit.concurrency-limit

As Nelson is executing various parts of its system asynchronously, the audit system has to be able to respond to multiple inbound callers at the same time. This configuration option controls the size of the thread pool that is used specifically for auditing. This should typically be a lower number, but greater than `2`.

```
audit.concurrency-limit = 4
```

#### audit.inbound-buffer-limit

The auditing system operates as a buffered queue. In order to define the behavior of Nelson, the buffer is capped to a specific value controlled by the `inbound-buffer-limit` field. In practice this should not be a problem and the default should suffice in nearly every case, but be aware that if Nelson were internally queuing more than the value specified here the queue will block until items have been consumed by one of the auditing processor threads.

```
audit.inbound-buffer-limit = 50
```


## Cleanup

The `cleanup` stanza controls how Nelson evaluates and executes the automatic cleanup operations on the service and job graphs.

* [cleanup.cleanup-delay](#cleanup-cleanup-delay)
* [cleanup.extend-deployment-time-to-live](#cleanup-cleanup-delay)
* [cleanup.initial-deployment-time-to-live](#cleanup-cleanup-delay)
* [cleanup.sweeper-delay](#cleanup-cleanup-delay)

#### cleanup.cleanup-delay

Upon what cadence should Nelson process the stack topology and look for stacks that have been marked as garbage and are pending deletion. This timeout affects how quickly a stack moves from the `ready` state to the `garbage` state, and in turn how quickly items that were `garbage` actually get reaped. 

```
cleanup.cleanup-delay = 10 minutes
```

#### cleanup.extend-deployment-time-to-live

When Nelson determines that a stack is still useful and is to avoid deletion, how long should that stack TTL be increased by? This parameter should be set to the longest time that you would be prepared to wait for a stack to be destroyed. Be aware that if the TTL is less than the `cleanup-delay` parameter then Nelson will find your stacks to be garbage and delete them. Change this parameter with caution and be sure to validate the behavior is what you want.

```
cleanup.extend-deployment-time-to-live = 30 minutes
```

#### cleanup.initial-deployment-time-to-live

When a stack is first launched by Nelson, how much of a grace period should be given before Nelson starts to subject the stack to the typical garbage collection process? This is the maximum time that Nelson will check for readiness - once the period elapses, if the stack is not in the `ready` state it will fall subject to the garbage collection process. 

```
cleanup.initial-deployment-time-to-live = 30 minutes
```

#### cleanup.sweeper-delay

Nelson is publishing a set of metadata about stacks to the configured routing subsystem of your choice. Cleaning the discovery / routing systems up is typically done on a slower cadence in the event that a stack needed to be redeployed or an error occurred and something needed to be recovered. The longer this period is set too, the more cruft will accumulate in your discovery / routing system (for example, Consul's KV storage). 

```
cleanup.sweeper-delay = 24 hours
```


## Database

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
database.connection = "jdbc:h2:file:/opt/application/db/nelson;DATABASE_TO_UPPER=FALSE;AUTO_SERVER=TRUE;"
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


## Datacenters
## Docker

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

## Email

Nelson can notify you by Email when changes to deployments happen. In order to do this, Nelson needs to be configured with an SMTP server. This is fully compatible with public cloud email offerings like SES (or any other provider that implements the SMTP protocol).

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



## Github

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

```
nelson.github.redirect-uri = "http://nelson.local/auth/exchange"
```

#### github.scope


```
nelson.github.scope  = "repo"
```

#### github.access-token


```
nelson.github.access-token = "replaceme"
```

#### github.system-username

Configure the correct 

```
nelson.github.system-username = "nelson"
```

## Network

Specify the networking options used by Nelson, including the network interface the JVM binds too, port binding, and the address Nelson advertises in its API. 

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

Whilst not recommended, but you can also use an IP address here if you do not have an internal domain name server. 

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

## Pipeline
## Security
## Slack
## Templating
## User Interface
## Workflow Logger
