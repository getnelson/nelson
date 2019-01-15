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
- Nomad
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

* [default-namespace](#default-namespace)
* [discovery-delay](#discovery-delay)
* [manifest-filename](#manifest-filename)
* [proxy-port-whitelist](#proxy-port-whitelist)
* [readiness-delay](#readiness-delay)
* [timeout](#timeout)

#### Default Namespace 

upon receiving a github release event, where should Nelson assume the
application should get deployed too. This can either be a root namespace,
or a subordinate namespace, e.g. `stage/unstable`... its arbitrary, but the
namespace must exist (otherwise Nelson will attempt to create it on boot)

```
nelson.default-namespace = "dev"
```

#### Discovery Delay

The frequency on which should nelson write out the discovery / routing protocol information to your configured routing sub-system. This should typically not be set too high as it will affect the frequency and choppiness of updates in the runtime. For example, if you set the value to 10 minutes and wanted to migrate traffic in 30 minutes, you'd end up with 3 large "steps" in that traffic-shifting curve (assuming a linear specification). 

```
nelson.discovery-delay = 2 minutes
```

#### Manifest Filename

When Nelson fetches the repository manifest, this field controls what filename Nelson should be looking for. By default, the expectation is that this config is a path from the root of the repository, but can be specified with relative paths (from the root of any given repository).

```
nelson.manifest-filename = ".nelson.yml"
```

#### Proxy Port Whitelist

When using Nelson [load balancer](/getting-started/routing.html#load-balancers) feature, to avoid exposing random ports on the internet, administrators can keep a whitelist of the ports they are permitting to be exposed. This helps mitigate issues with security surface areas, whilst not restricting users to some hardcoded defaults.  

```
nelson.proxy-port-whitelist = [ 80, 443, 8080 ]
```

#### Readiness Delay

How frequently should Nelson run the readiness check to see if a service deployment is ready to take active traffic. How frequently this needs to be will largely be affected by the routing sub-system you have elected to use. For example, queries to Consul, vs queries to Kubernetes. 

```
nelson.readiness-delay = 3 minutes
```

#### Timeout

Controls how long Nelson should wait when talking to external systems. For example, when Nelson makes a call to Vault, or a scheduling sub-system, how long should it wait?

```
nelson.timeout = 4 seconds
```



## Auditing
## Cleanup
## Database
## Datacenters
## Docker
## Email

Nelson can notify you by Email when changes to deployments happen. In order to do this, Nelson needs to be configured with an SMTP server. This is fully compatible with public cloud email offerings like SES (or any other provider that implements the SMTP protocol).

* [email.host](#email.host)
* [email.port](#email.port)
* [email.from](#email.from)
* [email.user](#email.user)
* [email.password](#email.password)

#### email.host

```
nelson.email.host = "mail.company.com"
```

#### email.port

```
nelson.email.port = 9000
```

#### email.from

```
nelson.email.from = "nelson@example.com"
```

### email.user

```
nelson.email.user = "someuser"
```

#### email.password

```
nelson.email.password = "somepassword"
```

## Github


## Network

Specify the networking options used by Nelson, including the network interface the JVM binds too, port binding, and the address Nelson advertises in its API. 

* [bind-host](#bind-host)
* [bind-port](#bind-port)
* [external-host](#external-host)
* [external-port](#external-port)
* [idle-timeout](#idle-timeout)

#### Bind Host

What host - interface - should Nelson bind its JVM server process too.

```
nelson.network.bind-host = "0.0.0.0"
```

#### Bind Port

What TCP port should Nelson bind its JVM server process too. This is typically a non-privileged port (i.e. above port 3000).

```
nelson.network.bind-port = "9000"
```

#### External Host

Typically Nelson is hosted behind a reverse proxy, or otherwise bound to an internal address. In order for cookie retention to work for browsers, and for Nelson to rewrite its generated URLs to locations that are network accessible, the operator must tell Nelson what that external address is.

```
nelson.network.external-host = "nelson.yourco.com"
```

Whilst not recommended, but you can also use an IP address here if you do not have an internal domain name server. 

```
nelson.network.external-host = "10.10.1.11"
```

#### External Port

If using a reverse proxy, and `external-host` is set, what port should be used for external responses (i.e. HTTP 302 redirects and so forth). If external-port is not supplied, then we assume port 443 (https)

```
nelson.network.external-port = 443
```

#### Idle Timeout

`idle-timeout` defines how long should nelson wait before closing requests to the Nelson server process. Under the hood, this equates to `idle-timeout` on http4s' [BlazeBuilder](https://github.com/http4s/http4s/blob/master/blaze-server/src/main/scala/org/http4s/server/blaze/BlazeBuilder.scala))

```
nelson.network.idle-timeout = 60 seconds
```

#### Monitoring Port

Nelson uses [Prometheus](https://prometheus.io/) as its monitoring solution. Prometheus exposes its metrics for collection via a TCP port, and the `monitoring-port` allows you to configure what port that is.

```
nelson.network.monitoring-port = 5775
```

## Nomad

When using the Nomad scheduling subsystem, you may want to provide some static configuration options:

* [required-service-tags](#required-service-tags)

#### Required Service Tags

Nomad is integrated with Consul and the scheduler can register services deployed with Consul. When doing this, it is sometimes advantageous to apply some admin-configured service tags. 

```
nelson.nomad.required-service-tags = [ "foo", "bar" ]
```

## Pipeline
## Security
## Slack
## Templating
## User Interface
## Workflow Logger
