+++
layout = "single"
title  = "Development"
weight = 3
menu   = "main"
+++

<h1 id="development" class="page-header">Development</h1>

Nelson is written in [Scala](https://scala-lang.org), and built using [SBT](http://www.scala-sbt.org/). You will need to install SBT locally before you can start working with the Nelson codebase. Please [follow the install steps](http://www.scala-sbt.org/0.13/docs/Setup.html) to get setup.

<h2 id="running-locally" data-subheading-of="development">
  Running Locally
</h2>

Booting Nelson locally is fairly straightforward. First, obtain a [Personal Access Token for Github](https://github.com/settings/tokens). Once you have this, add it to your `~/.bash_profile` as the `GITHUB_TOKEN` environment variable

In addition, you are required to add the following environment variables:

```
# these can be randomly assigned strings
export NELSON_GITHUB_SECRET="XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"
export NELSON_GITHUB_TOKEN="XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"
# the value here should be the PAT that you got from Github
export GITHUB_TOKEN="XXXXXXXXXXXXXXXXXX"
```

Next, add a line to your `/etc/hosts` such that you have a `nelson.local` domain pointing to your local loopback interface. This looks like:

```
127.0.0.1 nelson.local
```

This is the bare minimum required to run Nelson. You can then instruct Nelson to boot up by using the following command:

```
sbt http/reStart
```

Nelson will then boot up and be running on `http://nelson.local:9000`. Be aware that unless you have correctly configured a development OAuth application on Github for your local Nelson, you will get rejected from any activity related to Github. This is covered [in the operator guide](http://verizon.github.io/nelson/#install-authorize) section of the Operator Guide.

<h2 id="development-dependencies" data-subheading-of="development">
  Dependencies
</h2>

By default, the local development configuration assumes you're running Nomad on the loopback address, `127.0.0.1`. If you wish to point to a remote Nomad cluster, then you must set the following environment variables:

```
export NOMAD_ADDR=XXXXXXXX
export NELSON_NOMAD_DOCKER_HOST=XXXXXXXXXXXX
# the following are required if your docker registry that the
# remote cluster uses requires authentication
export NELSON_NOMAD_DOCKER_USERNAME=XXXXXXXXXXXX
export NELSON_NOMAD_DOCKER_PASSWORD=XXXXXXXXXXXX
```

Technically, you can run Nelson locally **without** the other system dependencies running locally - some functionality will of course not work. If the feature you're working on doesn't need those systems, please be aware your logs will contain errors reporting that those dependencies are not running. To remove these errors, install and run the following locally before booting Nelson.

Do be aware that you could also run these dependencies as containers but it can often become tricky with the bridge networking. This is something that is certainly possible to overcome, but it's more hassle than most people want when getting setup.

<h3 id="development-dependencies-consul" class="linkable">
  Consul
</h3>

Install Consul with `brew install consul`, or by downloading and installing [here](https://www.consul.io/downloads.html). Next, modify the Nelson config at `<project-dir>/etc/development/http/http.dev.cfg`: in the `consul` config, update `endpoint` to be:

```
datacenters.<yourdc>.consul.endpoint = "http://127.0.0.1:8500"
```

Then, run the Consul binary with `consul agent -dev`.

<h3 id="development-dependencies-vault" class="linkable">
  Vault
</h3>

Install Vault with `brew install vault`, or by downloading and installing [here](https://www.vaultproject.io/downloads.html). Modify the Nelson config at `<project-dir>/etc/development/http/http.dev.cfg`: in the `vault` config, update `endpoint` to be

```
endpoint = "http://127.0.0.1:8200"
```

Then, run the Vault binary with `vault server -dev`.

<h3 id="overview-features" class="linkable">
  Promtool
</h3>

To run tests, you must have `promtool` available on your path. Developers on a Mac may run this script to fetch `promtool` and install to `/usr/local/bin`:

```sh
./bin/install-promtool
```

If you prefer to install this binary manually, then please fetch it [from the prometheus site](https://prometheus.io/download/) and install at your favourite location on your `$PATH`.

<h2 id="development-conventions" data-subheading-of="development">
  Conventions
</h2>

There are a few conventions at play within the Nelson codebase:

* `JSON` responses from the API should use `snake_case`. This is because not all client-side scripting languages (namely, JavaScript) can handle keys that have dashes.

* Any functions that are going to be called for user or event actions should reside in the `Nelson` object and have a `NelsonK[A]` return value (where `A` is the type you want to return). Functions in this object are meant to assemble results from various other sub-systems (e.g. `Github` and `Storage`) into something usable by clients of the API.

<h2 id="development-using-docker" data-subheading-of="development">
  Using Docker
</h2>

If you're running docker on OSX, it's possible that your boot2docker/docker-machine does not have enough entropy to run the container, as we're using SecureRandom. If this is the case, then you can run the following container to augment the random number generation usign the following:

```
docker pull harbur/haveged
docker run --privileged -d harbur/haveged
```

Nelson can be started with the following command, assuming you're in the root of the Nelson source tree:

```shell
bin/run-with-docker
```

Where the `nelson.env` file looks like this (but with valid values, naturally):

```
NELSON_SECURITY_ENCRYPTION_KEY=xxxxxxxxxxxx
NELSON_SECURITY_SIGNATURE_KEY=xxxxxxxxxxxx
NELSON_GITHUB_SECRET=xxxxxxxxxxxx
NELSON_GITHUB_CLIENT_ID=xxxxxxxxxxxx
NELSON_DOMAIN=xxxxxxxxxxxx
GITHUB_USER=xxxxxxxxxxxx
GITHUB_TOKEN=xxxxxxxxxxxx
```

The session encryption, signing, and verification keys can be generated by running `bin/generate-keys`.  The below is an example.  Run this yourself!  Do not use these!  Do not share your signing key or encryption key!

```sh
export NELSON_SECURITY_ENCRYPTION_KEY=WWD4N/4oxgPmGlai/MW4Hw==
export NELSON_SECURITY_SIGNATURE_KEY=YNhJUcF8ggQ7HoWkmGqaxw==
```

<h2 id="development-database" data-subheading-of="development">Database</h2>

Nelson's primary data store is a H2 database. This deliberately doesn't scale past a single machine, and was an intentional design choice to limit complexity in the early phases of the project. With that being said, H2 is very capable, and for most users this will work extremely well. If Nelson were reaching the point where H2 on SSD drives were a bottleneck, you would be doing many thousand of deployments a second, which is exceedingly unlikely.

If you start to contribute to Nelson, then its useful to understand the data schema, which is as follows:

<div class="clearing">
  <img src="images/erd.png" width="100%" />
</div>

As can be seen from the diagram, Nelson has a rather normalized structure. The authors have avoided denormalization of this schema where possible, as Nelson is not in the runtime hot path so the system does not suffer serious performance penalties from such a design; in short it will be able to scale far in excess of the query and write load Nelson actually receives.


<h2 id="development-known-issues" data-subheading-of="development">Known Issues</h2>

1. Upon receiving notification of a release event on Github, Nelson converts this to events published to its internal event stream (called `Pipeline`). `Pipeline` and messages on it, are not durable. If Nelson is processing a message (or has messages queued because of contention or existing backpressure), and an outage / upgrade or any reason that causes a halt to the JVM process, will loose said messages.

1. Nelson does not have a high-availability data store. As mentioned above in the database section, this is typically not a problem, but should be a consideration. In the future, the authors may consider upgrading Nelson so it can cluster, but the expectation is that scaling-up will be more cost-effective than scaling-out for most users. Nelson will currently eat up several thousand deployments a minute, which is larger than most organizations will ever reach.


<h1 id="cli" class="page-header">Command Line</h1>

The [Nelson CLI](https://github.com/Verizon/nelson-cli) is useful for debugging the Nelson API locally. Particularly useful are the client's `--debug` and `--debug-curl` flags. You can read about them in the [client's documentation](http://verizon.github.io/nelson-cli/). One option that you need to pay attention to for local usage is the `--disable-tls` flag on the `login` subcommand. To login to a local Nelson instance, you should run the following:

```
nelson login --disable-tls nelson.local:9000
```

It's important to note that to use the API locally, a change to the development config at `<project-dir>/etc/development/http/http.dev.cfg` is needed. Add the following line inside the `nelson.github` config:

```
organization-admins = [ "<your-github-handle-here>" ]
```

This ensures that when you login via the UI that you are specified as an admin and do not have limited access to the operations you can locally perform.

<h1 id="documentation" class="page-header">Documentation</h1>

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

