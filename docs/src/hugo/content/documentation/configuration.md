---
layout: "single"
toc: "false"
title: Configuration
preamble: >
  For installation instructions, see the [user guide](/getting-started/install.html) section. This reference guide covers the various configuration options Nelson can have in its configuration file and explains why you might want them.
contents:
- Overview
- Network
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

