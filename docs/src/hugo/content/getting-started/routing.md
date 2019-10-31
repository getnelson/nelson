---
layout: "single"
title: Routing
preamble: >
  Within the Nelson ecosystem, Routing encompasses two critical parts of the system: internal communication (so-called service-to-service), and ingresses traffic from outside the dynamic environment via so-called "load balancers". Given Nelson's strong belief in immutable infrastructure, the way in which these routing systems operate are cutting edge, leveraging the latest technology to get the job done effectively.
menu:
  main:
    parent: gs
    identifier: gs-routing
    url: /getting-started/routing.html
    weight: 6
---

Nelson supports a dynamic discovery system, which leverages semantic versioning and control input from manifest declarations to make routing choices. This part of the system is perhaps one of the most complicated elements, so a brief overview is included here. To give a general overview, figure 1.1 highlights the various moving parts.

<div class="clearing">
  <img src="/img/routing-design.png" />
  <small><em>Figure 1.1: container routing</em></small>
</div>

Reviewing the figure, the runtime workflow works as follows:

<span style="font-weight: bold; color: red">A.</span> The container is launched by the scheduler and your templating engine of choice obtains a certificate from the PKI backend in Vault after safely negotiating access to Vault with its secure token.

<span style="font-weight: bold; color: red">B.</span> Envoy boots using the certificates obtained from the first step, and then initializes the configured `xDS` provider. Whatever control plane you choose to use ([Istio](https://istio.io/), [Rotor](https://github.com/turbinelabs/rotor) etc.) acts as a mediator between Nelson and the runtime Envoy mesh - Nelson simply send instructions to the control plane, and the control plane in turn dynamically streams updates to the Envoy mesh.

<span style="font-weight: bold; color: red">C.</span> Application boots and goes about its business. In the event the application is attempting to make a call to another service, it actually calls to "localhost" from the containers perspective, and hits the locally running Envoy instance. Envoy then wraps the request in mutual SSL and forwards it to the target service instance. In this way, the application itself is free from knowing about the details of mutual authentication and certificate chains. All calls made via Envoy are automatically circuit-broken and report metrics via StatsD. This is rolled up to a central StatsD server.

## Load Balancers

In addition to service to service routing, Nelson also supports routing traffic into the runtime cluster via "load balancers" (LBs). Nelson treats these as a logical concept, and supports multiple backend implementations; this means that if Nelson has one datacenter in AWS, it knows about [NLB](https://aws.amazon.com/elasticloadbalancing/) and so forth. If, however, you have another datacenter not on a public cloud, Nelson can have alternative backends that do the needful to configure your datacenter for external traffic. The workflow at a high-level is depicted in figure 1.2.

<div class="clearing">
  <img src="/img/lbs.png"/>
  <small><em>Figure 1.2: load balancer overview</em></small>
</div>

Typically load balancers are static at the edge of the network because external DNS is often mapped to them. This is clearly a mismatch between statically configured DNS and the very dynamic, scheduler-based infrastructure Nelson otherwise relies upon. To bridge this gap, Nelson makes the assumption that the LB in question is capable of dynamically updating its runtime routes via consuming the Lighthouse discovery protocol.

### Protocol

Nelson also publishes static configuration pertaining the load balancer before it's launched into the datacenter. The protocol is as follows and is published to Consul's KV store at `nelson/v1/loadbalancers/<lb-name>`:

```
[
  {
    "port_label": "default",
    "frontend_port": 8444,
    "service_name": "http-service",
    "major_version": 1
  }
]
```

Whilst the fields should be self-explanatory, here are a list of definitions:

<table class="table table-striped">
  <thead>
    <tr>
      <td width="20%"><strong>Field</strong></td>
      <td><strong>Description</strong></td>
    </tr>
  </thead>
  <tbody>
    <tr>
      <td><code>port_label</code></td>
      <td>Port label used to describe this particular port in the manifest definition. All ports require a named label.</td>
    </tr>
    <tr>
      <td><code>frontend_port</code></td>
      <td>Port used to expose traffic on the outside of the load balancer. The available ports are restricted by the configuration parameter <code>nelson.proxy-port-whitelist</code>.</td>
    </tr>
    <tr>
      <td><code>service_name</code></td>
      <td>The service to route traffic to.</td>
    </tr>
    <tr>
      <td><code>major_version</code></td>
      <td>The major version the load balancer is bound to. Note, a load balancer's lifecycle is bound to the major version of the service it is proxying</td>
    </tr>
  </tbody>
</table>

### Amazon Web Services

Nelson has first-class support for spawning load balancers on Amazon. When using Nelson with a datacenter located in AWS, the "load balancer" setup looks like this:

<div class="clearing">
  <img src="/img/lbs-aws.png" width="40%"  />
  <small><em>Figure 1.3: load balancer on AWS</em></small>
</div>

Exactly how the proxy implementation is done is not something Nelson cares about. From Nelson's perspective, all that matters is that a logical traffic ingress point exists. In practical terms, you can bake an AMI that has your proxy software configured arbitrarily, it could be in a host-mode container, it could be running native: it is an implementation detail.

In order for Nelson to be able to spawn ELBs, you will need to give Nelson credentials for AWS using the following [IAM policy document](https://docs.aws.amazon.com/IAM/latest/UserGuide/access_policies.html):

```
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "ec2:DescribeAccountAttributes",
        "ec2:DescribeAddresses",
        "ec2:DescribeInternetGateways",
        "ec2:DescribeSecurityGroups",
        "ec2:DescribeSubnets",
        "ec2:DescribeVpcs",
        "iam:CreateServiceLinkedRole",
        "autoscaling:CreateAutoScalingGroup",
        "autoscaling:DeleteAutoScalingGroup",
        "autoscaling:DescribeAutoScalingGroups",
        "autoscaling:DescribeAutoScalingInstances",
        "autoscaling:DescribeLoadBalancers",
        "autoscaling:SetDesiredCapacity",
        "autoscaling:UpdateAutoScalingGroup",
        "elasticloadbalancing:CreateLoadBalancer",
        "elasticloadbalancing:CreateLoadBalancerListeners",
        "elasticloadbalancing:DeleteLoadBalancer",
        "elasticloadbalancing:DeleteLoadBalancerListeners",
        "elasticloadbalancing:DeregisterInstancesFromLoadBalancer",
        "elasticloadbalancing:DescribeLoadBalancers",
        "elasticloadbalancing:DescribeTags",
        "elasticloadbalancing:DetachLoadBalancerFromSubnets",
        "elasticloadbalancing:RegisterInstancesWithLoadBalancer"
      ],
      "Resource": "*"
    }
  ]
}
```

### Alternative Implementations

At the time of writing only the AWS load balancers were natively supported. Supporting additional modes of load balancing is straightforward but left as an exercise for the reader. Looking toward the future it probably would make sense for Nelson to support the prominent cloud providers natively, but that work is not currently in the roadmap.
