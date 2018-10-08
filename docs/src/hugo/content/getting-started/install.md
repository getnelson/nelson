---
layout: "single-with-toc"
title: "Install"
preamble: >
  Nelson is made up of two primary components: the server and the client. This guide shows you how to install and configure both of these, along with useful tools from the broader Nelson eco-system.
contents:
- Nelson Command Line
- Slipway Command Line
- Nelson Server
menu:
  main:
    parent: gs
    identifier: gs-install
    url: /getting-started/install.html
    weight: 1
---

## Nelson Command Line

The primary mode of interacting with Nelson is via a command line interface (CLI). The command line client provides most of the functionality the majority of users would want of Nelson. A future version of Nelson will also have a web-based user experience, which will have more statistical reporting functions and tools for auditing.

Choose your [platform specific download](/downloads.html) or have our handy script automatically choose the rightdownload for you:

```
curl -GqL https://raw.githubusercontent.com/getnelson/cli/master/scripts/install | bash
```

This script will download and install the latest version and put it on your `$PATH`. It is safe to rerun this script to update nelson-cli at a future date.

<div class="alert alert-warning" role="alert">
⛔&nbsp; We do not endorse piping scripts from the wire to bash. You should read the script before executing the command.
</div>

### Using the CLI

Before getting started, ensure that you have completed the following steps:

1. [Obtain a Github personal access token](https://help.github.com/articles/creating-an-access-token-for-command-line-use/) - ensure your token has the following scopes: <br /> `repo:*`, `admin:read:org`, `admin:repo_hook:*`.

2. Set the Github token into your environment: `export GITHUB_TOKEN=XXXXXXXXXXXXXXXX`

3. `nelson login nelson.example.com` (where nelson.example.com is replaced with the domain of the Nelson server you're trying to access), then you're ready to start using the other commands! If you're running the Nelson service insecurely - without SSL - then you need to pass the `--disable-tls` flag to the login command (this is typically only used for local development, and is not recomended for production usage)

You're ready to start using the CLI. The first command you should execute after install is `nelson whoami` which allows you to securely interact with the remote Nelson service and validate that you have successfully logged in.

<div class="alert alert-info" role="alert">
⚠️ &nbsp; Note that currently the Nelson client can only be logged into <strong>one</strong> remote <em>Nelson</em> service at a time.
</div>

If you encounter problems with the CLI, be aware of the following options which aid in debugging:

*  `--debug`: this option dumps the wire logs of the underlying network client so you can see what requests and responses the CLI is handling.

* `--debug-curl`: this option shows you the comparitive cURL command to make that would match functionally what Nelson CLI is doing. This is typically very useful for debugging purposes.

## Slipway Command Line

[Github releases](https://help.github.com/articles/creating-releases/) are the "trigger" for whole Nelson process to start deploying a given system. This small file-based protocol (called a [Deployable](/getting-started/deployables.html)), whilst simple, is essentially the "glue" between your continuous integration system and Nelson. To automate this integration, the Nelson ecosystem provides a convenient tool for creating releases called `slipway`.

The `slipway` tool can be installed by running the following:

```
curl -GqL https://raw.githubusercontent.com/getnelson/slipway/master/scripts/install | bash
```

It is safe to rerun this script to keep `slipway` current. If you have the source code checked out locally, you need only execute: `scripts/install` to install the latest version of `slipway`.

## Nelson Server

Lorem ipsum dolor sit amet, consectetur adipiscing elit. Maecenas eu commodo nisi. Etiam sagittis enim purus, id tristique ante tincidunt id. Nunc placerat velit neque. Integer finibus velit nec vestibulum elementum. Mauris vel tempor velit. Nam sodales malesuada purus vel bibendum. Sed eu mauris sit amet nulla sodales imperdiet.

### Requirements

Nelson has reasonably small runtime requirements. Nelson is typically consuming CPU and memory resources for its background tasks, and uses local disk storage as a scratch space whilst replicating containers to a remote registries in the target datacenter(s). With this in mind, the following machine specifications are recommended:

* 8-16GB of RAM
* 100GB of disk space (preferably SSDs)
* Ubuntu 18.04 LTS (or latest Ubuntu LTS as needed)
* Docker

It is strongly advised to **not** use a RedHat-based OS for running Nelson. After a great deal of testing, Debian-based OS has been found to be orders of magnitude faster when running Docker than RedHat counterparts. This seems to be related to the interplay of the I/O subsystems, but the author was unable to find a clear "smoking gun" for this huge delta in performance. If users would like to use Red Hat, please reach out to the Nelson team for operational advice (please also note that the container ecosystem is a moving target, so you should conduct your own testing to validate performance in your environment).

### Authorize with Github

Nelson is implemented as a Github OAuth application, and it requires a one-time setup when installing it into your Github organization. If you are not familiar with how to setup a Github application, please see the [GitHub documentation site](https://developer.github.com/guides/basics-of-authentication/) for details. This process should work equally well on both [github.com](https://github.com) and Github Enterprise.

When registering Nelson with Github, the exact domain on which the Nelson process is reachable should be specified (take care select the right protocol - `http` vs `https`), and the callback URL should be: `https://nelson.foo.net/auth/exchange`. From a networking standpoint, provided the Github instance can reach the Nelson server, and the domain specified in the OAuth application matches that being used by the client, then the system should work. **If you are using `github.com`, then your Nelson instance must be accessible from the Github outbound NAT address**. If you encounter problems during the setup, the Nelson logs should contain information about the error.

Once setup, Github will present you with a `client_id` and a `client_secret` for the application. These are needed by the Nelson configuration, along with a system OAuth token Nelson can use to execute asynchronous actions on the applications behalf (i.e. without direct user interaction). Run the following to generate the access token:

```
curl -s https://raw.githubusercontent.com/getnelson/nelson/master/bin/generate-token | bash
```

If you have the Nelson source checked out locally on your development machine, then you can run `./bin/generate-token` if you would rather not pipe scripts from the internet to bash. The script will ask you a series of questions, and produce a OAuth token. This token - along with the `client_id` and `client_secret` - needs to be set in the Nelson configuration file like so:

```
nelson{
  github {
    client-id = "XXXXXXX"
    client-secret = "YYYYYYYYYY"
    redirect-uri = "http://nelson.yourcompany.com/auth/exchange"
    access-token = "TOKEN-FROM-SCRIPT"
  }
}
```

Please see the [configuration section](#instalation-configuration) for details on where and how the Nelson configuration is loaded.

### Configuration

Nelson has a range of configuration options specified using the [Knobs](https://verizon.github.io/knobs/) format, read from fils on disk. Nelson can either be told where to load configuration from, but if no path is specified then it will assume that it should be loaded from `/opt/application/conf/nelson.cfg`.

Many of the Nelson defaults will be fine for the majority of users. There are however, several configuration sections which must be updated by the operator to ensure Nelson works as expected. Here's an example of the minimal configuration file:

```
nelson {
  network {
    # Typically Nelson is hosted behind a reverse proxy, or otherwise bound
    # to an internal address. In order for cookie retention to work for browsers,
    # and for Nelson to rewrite its generated URLs to locations that are
    # network accessible, the operator must tell Nelson what that external address is.
    external-host = "nelson.yourco.com"
  }
  github {
    # If you're operating Nelson in conjunction with Github Enterprise, then you
    # need to be sure you've set this value to the host (without protocol prefix).
    # For example <code>github.yourco.com</code>
    domain = "github.yourco.com"
    # The client_id and client_secret provided to you from Github, whilst
    # setting up the application. See the "Authorize with Github" section for more
    # on the paramaters below:
    client-id = "XXXXXXX"
    client-secret = "YYYYYYYYYY"
    redirect-uri = "http://nelson.yourcompany.com/auth/exchange"
    access-token = "TOKEN-FROM-SCRIPT"
    # Certain operations in Nelson make serious state modifications, and as such
    # these are restricted to a specific set of "admin" users. The operator must
    # specifiy these users at boot time. Changing these values whilst Nelson is
    # running will have absolutely no effect (i.e. the process must be rebooted
    # for changes to take effect)
    organization-admins = [ "user1", "user2" ]
  }
  security {
    # These keys are used to securely encrypt the Nelson session token that are
    # generated when a user logs into the system. The keys themselves must be
    # at least 24 characters long. A helpful script in ./bin/generate-keys can
    # be used to automatically generate the right values.
    # *****DO NOT USE THESE VALUES*****
    encryption-key = "4e08LjzM42El6+Gbfp9XaQ=="
    signature-key = "ZRq3NkqbccXE7+ZkRirjOg=="
  }
  docker {
    # Decide how you would like Nelson to attempt to interact with Docker.
    # This is typically done either by a unix domain socket, or via a tcp
    # endpoint. Nelson itself is controling docker via the CLI, so it will
    # use whatever the configuration is for a given host.
    connection = "unix:///path/to/docker.sock"
    # connection = "tcp://0.0.0.0:12345"
  }

  datacenters {
    # The name of this key must be a DNS value name. Typically you want to call your
    # datacenters something logical, but keep it short. AWS for example, uses
    # us-east-1, us-west-1 etc.
    # Datacenter names must be lowercase, and not include specical characters.
    texas {

      docker-registry = "sxxxx.net/bar"

      # this is the DNS domain used for a given datacenter. When writing out the lighthouse
      # routing graph or similar, Nelson will use this value as the TLD for a given datacenter
      # "world". An assumption is being made here that you're running DNS fowrading for
      # the consul service: https://www.consul.io/docs/guides/forwarding.html
      domain = "service.example.com"

      # What should the default traffic shifting policy be in this particular DC.
      # Configurable per-DC such that you might have production DCs, vs dev DCs
      # (of course remember DC is a virtual concept)
      traffic-shift {
        policy = "atomic"
        duration = 2 minute
      }

      infrastructure {
        scheduler {
          scheduler = "kubernetes"
          kubernetes {
            # is nelson hosted on the cluster it is managing?
            in-cluster = false
            # in the event that Nelson is running on a substrate
            # outside of the deployment clusters, specify the path
            # to the kubeconfig which will dictate how kubectl does
            # authentication with the cluster and so forth.
            kubeconfig = /path/to/.kube/config
          }
        }

        vault {
          endpoint = "https://vault.california.service"
          # How much grace shall we give when talking to Vault.
          # Depending upon your link speed (e.g. if you're tunneling via SSH or
          # IPSEC it might be slower)
          timeout   = 1 second
          # Nelson will be administering Vault, creating policies and such. With this
          # in mind, you need to ensure that Nelson has a vault authentication token
          # which permits these operations.
          auth-token = "..."
        }
      }
      policy {
        # When Nelson manages policies and credential stores in Vault, where should
        # it assume it can find them. This simplistic substitution syntax allows you
        # to control how Nelson will read and write to and from Vault storage paths.
        resource-creds-path = "nelson/%env/%resource%/creds/%unit%"
        # If you have Vault configured to generate SSL certificates, then in order for
        # deployed containers to be able to generate these certs at runtime, you need
        # to have Nelosn include that backend in the policy it generates. If you have
        # a global PKI backend called "sslpki", then you can statically set the
        # backend like this:
        # pki-path = "sslpki"
        # In the event you have a dynamic "per-environment" SSL security world, then
        # this simple substitution language allows you to tell Nelson how it should
        # discover the PKI backend path in vault.
        pki-path = "%env%_certificates"
      }
    }
  }
}
```

The following table gives an explanation of the configuration file sections and their purpose, but for a full explanation and all available configuration options please see [defaults.cfg](https://github.com/getnelson/nelson/blob/master/core/src/main/resources/nelson/defaults.cfg) in the source tree.

<table class="table table-striped">
  <thead>
    <tr>
      <td width="28%"><strong>Section</strong></td>
      <td><strong>Description</strong></td>
    </tr>
  </thead>
  <tr>
    <td><code>nelson.network</code></td>
    <td>Specifiy the networking options used by Nelson, including the network interface the JVM binds too, port binding, and the address Nelson advertises in its API</td>
  </tr>
  <tr>
    <td><code>nelson.security</code></td>
    <td>Control the encryption keys used by Nelson for session token encryption, token signing and expiration.</td>
  </tr>
    <tr>
    <td><code>nelson.github</code></td>
    <td>Most installations will absolutely have to configure this section, as the OAuth application identifiers are different for every setup. Whilst Nelson will typically execute actions on Github with the credentials of the active session, this section also allows you to configure a "nelson user" for your Github integration that Nelson will use to interact with Github asynchronously, for non-user invoked actions. In future this will be replaced by deriving an OAuth token on startup from the configured Nelson OAuth application.</td>
  </tr>
    <tr>
    <td><code>nelson.docker</code></td>
    <td>Instructs Nelson how it should connect to the docker daemon it should use for replicating containers to the remote datacenter. Typically this is in the style of `unix:///path/to/socket` or `tcp://127.0.0.1:5678`.</td>
  </tr>
    <tr>
    <td><code>nelson.database</code></td>
    <td>Configure the JDBC connection string Nelson will use. Typically this is not configured by users.</td>
  </tr>
    <tr>
    <td><code>nelson.timeout</code></td>
    <td>Global timeout Nelson should apply for I/O operations to its dependent systems (Github, Nomad etc)</td>
  </tr>
    <tr>
    <td><code>nelson.cleanup</code></td>
    <td>Controls the grace periods Nelson gives to new applications, and the size of the bump period when extending expirations. Fields of particular user interest here are `initial-deployment-time-to-live` and `extend-deployment-time-to-live`.</td>
  </tr>
  <tr>
    <td><code>nelson.pipeline</code></td>
    <td>Whilst typically not edited by users, these settings control the amount of back-pressure in the Nelson deployment pipeline, and the level of concurrency Nelson uses when doing deployments. Altering these values from the default requires taking into account the latency and network I/O limitations of the host, as more concurrency will mean concurrent replication of containers (which is typically limited by docker defaults, unless overridden by a specific installation).</td>
  </tr>
  <tr>
    <td><code>nelson.template</code></td>
    <td>When using the <a href="https://github.com/hashicorp/consul-template" target="_blank">consul-template</a> linting feature, this block must be configured. The defaults are usually fine for the majority of users.</td>
  </tr>
  <tr>
    <td><code>nelson.workflow-logger</code></td>
    <td>Typically not altered by users, but these settings control where and how the workflow logger stores the execution logs for a given deployment. These end up being small text files on disk, and the buffer size controls how frequently the queue is flushed to the file writing process.</td>
  </tr>
  <tr>
    <td><code>nelson.email</code></td>
    <td>If you wish to configure Nelson with email notifications of job status, then you must configure this section with valid SMTP details for an email relay that Nelson can access.</td>
  </tr>
    <tr>
    <td><code>nelson.slack</code></td>
    <td>Nelson can notify you about deployment actions via Slack. For this integration to work a slack team admin must generate a Slack webhook URL and have Nelson configured to use this value.</td>
  </tr>
    <tr>
    <td><code>nelson.datacenters.YOURDC</code></td>
    <td>Various subsections that configure the credentials and endpoints for your scheduler implementation, consul, vault etc.</td>
  </tr>
    <tr>
    <td><code>nelson.ui</code></td>
    <td>Nelson ships with a bare-bones UI to support login via Github. You can however easily override the UI and supply whatever interface you want, utilizing the Nelson REST interface.</td>
  </tr>
    <tr>
    <td><code>nelson.nomad</code></td>
    <td>Some static configuration properties for using Nomad. For example, `required-service-tags` are additional identifiers that Nelson will attach to the Consul service catalog entry, allowing you identify workloads from any other records in the service catalog (typically exceedingly useful for monitoring or migration auditing)</td>
  </tr>
    <tr>
    <td><code>nelson.readiness-delay</code></td>
    <td>When a unit exposing ports is deployed, how frequently should Nelson check if the consul health checks have transitioned to "healthy". Nelson requires a majority of container instances to be reporting healthy in consul before transitioning the stack state from <code>Warming</code> to <code>Ready </code></td>
  </tr>
    <tr>
    <td><code>nelson.discovery-delay</code></td>
    <td>The cadence that Nelson should recompute the runtime routing graph and update the consul values for a specific stack. The more stacks you have, the longer this work will take so be sure to set the value here at an appropriate rate relevant to your deployment size. Be aware that the longer this delay is, the more "choppy" your short-duration traffic bleeds will be (this typically isn't an issue, as short-duration traffic shifting is discouraged as it can result in inbound traffic synfloods.).</td>
  </tr>
  <tr>
    <td><code>nelson.lb-port-whitelist</code></td>
    <td>Control the "outside" ports you want to allow users to expose from load balancers. Typically it is undesirable to have end-users be able to expose any random port at the edge of your network, as this can drastically increase security attack surface and make security auditing challenging. Instead, set a known set of ports that are agreed accross teams that will be used for external traffic ingress.</td>
  </tr>
   <tr>
    <td><code>nelson.manifest-filename</code></td>
    <td>By default, Nelson will look for a `.nelson.yml` file in the root of a repository, but if you'd prefer to use a different file for some reason then just tell Nelson what that filename should be here.</td>
  </tr>
  <tr>
    <td><code>nelson.default-namespace</code></td>
    <td>Upon receiving a github release event, where should Nelson assume the application should get deployed too. This can either be a root namespace, or a subordinate namespace, e.g. `stage/unstable`... its arbitrary, but the namespace must exist (Nelson will attempt to create the specified namespace on bootup).</td>
  </tr>
</table>

This table should be considered an overview, and not an exhaustive list of the configuration options Nelson exposes. The author does not expect most users to be altering the default values for the majority of fields, but know that these values exist.

### Running Standalone

Typically Nelson is operated and installed as a `systemd` unit, but users are free to configure or operate the system however they please (`initV`, `upstart` etc). The docker command you use to start the system will be very similar regardless of the init system you choose. Consider the systemd service definition (`systemd` is the default init system on modern linux OS). The following unit definition assumes localhost TCP access to the docker daemon (instead of using the unsafe `docker` user group - see [here for more](https://coreos.com/os/docs/latest/customizing-docker.html))

```
[Unit]
Description=Nelson Server
After=docker.service
Requires=docker.service

[Service]
User=${RUNTIME_USER}
TimeoutStartSec=0
Restart=on-failure
RestartSec=10s
ExecStop=-/usr/bin/docker -H tcp://127.0.0.1:2375 stop -t 5 nelson
ExecStartPre=-/usr/bin/docker -H tcp://127.0.0.1:2375 pull getnelson/nelson:latest
ExecStart=/usr/bin/docker -H tcp://127.0.0.1:2375 run --rm \
--net=host \
--name nelson \
-e KUBECONFIG=/opt/application/conf/kubeconfig \
-v "/etc/nelson/nelson.cfg":/opt/application/conf/nelson.cfg \
-v "/etc/nelson/kubeconfig":/opt/application/conf/kubeconfig \
-v "/var/nelson/db":/opt/application/db \
-v "/var/nelson/log":/var/nelson/log \
getnelson/nelson:latest

[Install]
WantedBy=multi-user.target
```

This command - or a command like it - can be run from any system with Docker installed, and the file mounts supplied (using `-v`) are used so that the Nelson database and related configuration files are stored on the host system, and not within the container. This allows Nelson to persist state over process reboots.

<div class="alert alert-warning" role="alert">
Nelson's database is a simple <a href="http://www.h2database.com/">H2</a> file-based datastore. Nelson is intended to be running as a singleton and currently does not support clustering. Support for high-availability deployment modes are planned for a future release, but typically this is not needed as outages of Nelson have no critical affect on the datacenter runtime.
</div>

### Running on Kubernetes

Nelson can also be operated on top of Kubernetes (even if that same cluster is the one being managed by Nelson). When deploying to Kubernetes, Nelson operates like any other container applciation with the exception that it requires the use of a [Persistent Volume Claim](https://kubernetes.io/docs/concepts/storage/persistent-volumes/) to which Nelson can journal its state (database, job logs etc).

As the Kubernetes configurations are rather verbose, the Nelson teams has provided a set of example configuration files [in the getnelson/kubernetes-configuration](https://github.com/getnelson/kubernetes-configuration) repository on Github.

### Installation Complete

With those steps complete, you should be able to browse to the Nelson URL and login using your Github account. If you encounter problems during this setup process, please check the logs from the Nelson container using `journalctl` on the host (assuming modern Linux OS with `systemd`). Alternatively, install any of the generic log forwarding products (splunk, fluentd, logstash etc) to export the logs into an indexed aggregator (this is highly recommended for production usage).
