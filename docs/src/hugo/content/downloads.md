---
layout: "single"
title: Downloads
preamble: >
  Nelson comes in two parts: the server and the client. Most users will be simply using a Nelson server that was setup by an operator in your organization, so the [client](#client) will be what most people want to download.
---

## Client

The client is shipped as a static binary that comes pre-compiled for a variety of operating systems. The tables below enumerate the direct-download sites for the latest version (presently v{{< version >}}).

{{< downloads_client >}}{{< cli_version >}}{{< /downloads_client >}}

## Server

Nelson's server is distributed as a Docker container, which makes for an easy instalation and operation. Docker containers require [the Docker client](https://docs.docker.com/install/) to `docker pull` the image as needed.

{{< downloads_server >}}{{< version >}}{{< /downloads_server >}}
