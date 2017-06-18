+++
layout = "single"
title  = "Internals"
weight = 3
menu   = "main"
+++

<h1 id="database" class="page-header">Database</h1>

*Nelson*'s main mode of data storage is by way of a H2 database. At the time of writing Nelson was designed to explicitly be not high availablility and the intention is that Nelson would be deployed to a single machine. The Nelson process itself then relies on `systemd` for supervision. The schema in use looks like:

<div class="clearing">
  <img src="images/erd.png" width="100%" />
</div>

As can be seen from the diagram, Nelson has a rather normalized structure. Given Nelson is not in the runtime hot path, the system does not suffer serious performance penalties from such a design, and will be able to scale far in excess of the query and write load Nelson actually receives.

<h1 id="known-issues" class="page-header">Known Issues</h1>

1. Upon receiving notification of a release event on Github, Nelson converts this to events published to its internal event stream (called `Pipeline`). `Pipeline` and messages on it, are not durable. If Nelson is processing a message (or has messages queued because of contention or existing backpressure), and an outage / upgrade or any reason that causes a halt to the JVM process, will loose said messages.

1. Nelson does not have a high-availability data store. As mentioned above in the database section, this is typically not a problem, but should be a consideration. In the future, the authors may consider upgrading Nelson so it can cluster, but the expectation is that scaling-up will be more cost-effective than scaling-out for most users. Nelson will currently eat up several thousand deployments a minute, which is larger than most organizations will ever reach.
