---
layout: "single"
title: "Install"
preamble: >
  Lorem ipsum dolor sit amet, consectetur adipiscing elit. Maecenas eu commodo nisi. Etiam sagittis enim purus, id tristique ante tincidunt id. Nunc placerat velit neque. Integer finibus velit nec vestibulum elementum. Mauris vel tempor velit. Nam sodales malesuada purus vel bibendum. Sed eu mauris sit amet nulla sodales imperdiet.
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

To install the Nelson CLI run the following:

```
curl -GqL https://raw.githubusercontent.com/getnelson/nelson-cli/master/scripts/install | bash
```

This script will download and install the latest version and put it on your `$PATH`. We do not endorse piping scripts from the wire to `bash`, and you should read the script before executing the command. It will:

1. Fetch the latest version from Nexus

2. Verify the SHA1 sum

3. Extract the tarball

4. Copy nelson to `/usr/local/bin/nelson`

It is safe to rerun this script to update nelson-cli at a future date.

### Using the CLI

Before getting started, ensure that you have completed the following steps:

1. [Obtain a Github personal access token](https://help.github.com/articles/creating-an-access-token-for-command-line-use/) - ensure your token has the following scopes: <br /> `repo:*`, `admin:read:org`, `admin:repo_hook:*`.

2. Set the Github token into your environment: `export GITHUB_TOKEN=XXXXXXXXXXXXXXXX`

3. `nelson login nelson.example.com` (where nelson.example.com is replaced with the domain of the Nelson server you're trying to access), then you're ready to start using the other commands! If you're running the Nelson service insecurely - without SSL - then you need to pass the `--disable-tls` flag to the login command (this is typically only used for local development, and is not recomended for production usage)

You're ready to start using the CLI. The first command you should execute after install is `nelson whoami` which allows you to securely interact with the remote Nelson service and validate that you have successfully logged in.

<div class="alert alert-warning" role="alert">
⛔&nbsp; Note that currently the Nelson client can only be logged into <strong>one</strong> remote <em>Nelson</em> service at a time.
</div>

If you encounter problems with the CLI, be aware of the following options which aid in debugging:

*  `--debug`: this option dumps the wire logs of the underlying network client so you can see what requests and responses the CLI is handling.

* `--debug-curl`: this option shows you the comparitive cURL command to make that would match functionally what Nelson CLI is doing. This is typically very useful for debugging purposes.

## Slipway Command Line

TODO: Install slipway

## Nelson Server

Lorem ipsum dolor sit amet, consectetur adipiscing elit. Maecenas eu commodo nisi. Etiam sagittis enim purus, id tristique ante tincidunt id. Nunc placerat velit neque. Integer finibus velit nec vestibulum elementum. Mauris vel tempor velit. Nam sodales malesuada purus vel bibendum. Sed eu mauris sit amet nulla sodales imperdiet.

### Running Standalone

Lorem ipsum dolor sit amet, consectetur adipiscing elit. Maecenas eu commodo nisi. Etiam sagittis enim purus, id tristique ante tincidunt id. Nunc placerat velit neque. Integer finibus velit nec vestibulum elementum. Mauris vel tempor velit. Nam sodales malesuada purus vel bibendum. Sed eu mauris sit amet nulla sodales imperdiet.

### Running on Kubernetes

Lorem ipsum dolor sit amet, consectetur adipiscing elit. Maecenas eu commodo nisi. Etiam sagittis enim purus, id tristique ante tincidunt id. Nunc placerat velit neque. Integer finibus velit nec vestibulum elementum. Mauris vel tempor velit. Nam sodales malesuada purus vel bibendum. Sed eu mauris sit amet nulla sodales imperdiet.
