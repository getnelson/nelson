---
layout: "single"
title: Contributing
preamble: >
  Contributions to Nelson are very much welcome! If you're considering contributing to the project, feel free to swing by the [gitter chat room](https://gitter.im/getnelson/nelson) to disucss the changes before hand (perhaps someone else is already working on your issue!). Alternitivly, please [file an issue](https://github.com/getnelson/nelson/issues) and one of the Nelson team will endevour to get back to you as soon as possible. The sections below outline some of the conventions and useful information for Nelson developers.
contents:
- Development
- Command Line
- Documentation
menu:
  main:
    identifier: docs-contributing
    parent: docs
    url: /documentation/contributing.html
    weight: 7
---

## Development

Nelson is written in [Scala](https://scala-lang.org), and built using [SBT](http://www.scala-sbt.org/). You will need to install SBT locally before you can start working with the Nelson codebase. Please [follow the install steps](https://www.scala-sbt.org/1.x/docs/Setup.html) to get setup.

### Conventions

There are a few conventions at play within the Nelson codebase:

* `JSON` responses from the API should use `snake_case`. This is because not all client-side scripting languages (namely, JavaScript) can handle keys that have dashes.

* Any functions that are going to be called for user or event actions should reside in the `Nelson` object and have a `NelsonK[A]` return value (where `A` is the type you want to return). Functions in this object are meant to assemble results from various other sub-systems (e.g. `Github` and `Storage`) into something usable by clients of the API.

### Database

Nelson's primary data store is a H2 database. This deliberately doesn't scale past a single machine, and was an intentional design choice to limit complexity in the early phases of the project. With that being said, H2 is very capable, and for most users this will work extremely well. If Nelson were reaching the point where H2 on SSD drives were a bottleneck, you would be doing many thousand of deployments a second, which is exceedingly unlikely.

If you start to contribute to Nelson, then its useful to understand the data schema, which is as follows:

<div class="clearing">
  <img src="/img/erd.png" width="100%" />
</div>

As can be seen from the diagram, Nelson has a rather normalized structure. The authors have avoided denormalization of this schema where possible, as Nelson is not in the runtime hot path so the system does not suffer serious performance penalties from such a design; in short it will be able to scale far in excess of the query and write load Nelson actually receives.


### Known Issues

1. Upon receiving notification of a release event on Github, Nelson converts this to events published to its internal event stream (called `Pipeline`). `Pipeline` and messages on it, are not durable. If Nelson is processing a message (or has messages queued because of contention or existing backpressure), and an outage / upgrade or any reason that causes a halt to the JVM process, will loose said messages.

1. Nelson does not have a high-availability data store. As mentioned in the database section, this is typically not a problem, but should be a consideration. In the future, the authors may consider upgrading Nelson so it can cluster, but the expectation is that scaling-up will be more cost-effective than scaling-out for most users. Nelson will currently eat up several thousand deployments a minute, which is larger than most organizations will ever reach.

<hr />

## Command Line

The [Nelson CLI](https://github.com/getnelson/nelson-cli) is useful for debugging the Nelson API locally. Particularly useful are the client's `--debug` and `--debug-curl` flags. You can read about them in the [client's documentation](https://github.com/getnelson/nelson-cli#getting-started). One option that you need to pay attention to for local usage is the `--disable-tls` flag on the `login` subcommand. To login to a local Nelson instance, you should run the following:

```
nelson login --disable-tls nelson.local:9000
```

It's important to note that to use the API locally, a change to the development config at `<project-dir>/etc/development/http/http.dev.cfg` is needed. Add the following line inside the `nelson.github` config:

```
organization-admins = [ "<your-github-handle-here>" ]
```

This ensures that when you login via the UI that you are specified as an admin and do not have limited access to the operations you can locally perform.

<hr />

## Documentation

There are a couple of options for testing documentation locally. First you need to install [Hugo](https://gohugo.io/), which is a single, native binary and just needs to be present on your `$PATH`.

The most convenient method for viewing documentation locally is to run via SBT using the following command:

```
sbt docs/previewSite
```

This will open your default web browser with the documentation site, which is handy for locally viewing the docs. It does however *not* support dynamic reloading of pages when the source changes. Luckily this is supported by Hugo, and can easily be run with a script locally:

```
cd docs/src/hugo
hugo server -w -b 127.0.0.1 -p 4000
```

Hugo will automatically refresh the page when the source files are changed, which can be very helpful when one is itterating on the documentation site over time.

