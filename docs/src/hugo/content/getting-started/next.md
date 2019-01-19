---
layout: "single"
title: "Further Reading"
preamble: ""
menu:
  main:
    parent: gs
    identifier: gs-next
    url: /getting-started/next.html
    weight: 7
---

In conclusion, the manifest contains a variety of settings and options too numerous to mention in this introductory text. Suggested further reading [in the reference](/documentation/manifest.html) about the Nelson manifest answers the following common queries:

* [How do I declare a dependency on another unit?](/documentation/manifest.html#manifest-unit-dependencies)
* [How do I get alerted when my unit has a problem at runtime?](/documentation/manifest.html#manifest-unit-alerting)
* [How do I expose my service to the outside world?](/documentation/manifest.html#load-balancers)

Now that you have your `.nelson.yml` as you want it, add the file to the **root** of your source repository, and commit it to the `master` branch. Nelson will look at the repositories' `master` branch when it first attempts to validate your repository is something that is Nelson-compatible. You can check the validity of your manifest definition at anytime without checking in by using the Nelson CLI: `nelson lint manifest`.

Outside of this website there are also several blog posts and talks on Nelson, from design principles to implementation
to the system as a whole.

### Blog Posts

* [Nelson integrates Kubernetes](http://timperrett.com/2017/12/07/nelson-integrates-kubernetes) - Timothy Perrett, December 2017
* [Envoy with Nomad and Consul](http://timperrett.com/2017/05/13/nomad-with-envoy-and-consul) - Timothy Perrett, May 2017

### Talks

* [Nelson: Functional programming in system design](https://youtu.be/c_bD9N4A7rY) - Adelbert Chang, Scale by the Bay 2018 - [slides](https://speakerdeck.com/adelbertc/nelson-functional-programming-in-system-design-sbtb-2018) - more systems/workflow focused
* [Nelson: Functional programming in system design](https://youtu.be/t8KRo-DXnEo) - Adelbert Chang, Northeast Scala Symposium 2018 - [slides](https://speakerdeck.com/adelbertc/nelson-functional-programming-in-system-design) - more implementation focused
* [Persistent Data Structures in System Design](https://www.youtube.com/watch?v=exepvX_XnlM&feature=youtu.be&t=2m46s) - Adelbert Chang, East Bay Haskell Meetup January 2018 - [slides](https://speakerdeck.com/adelbertc/persistent-data-structures-in-system-design)
* [Online Experimentation with Converged, Immutable Infrastructure](https://youtu.be/PyXF0k2DUG0) - Timothy Perrett, HashiConf 2017 - [slides](https://www.slideshare.net/timperrett/online-experimentation-with-immutable-infrastructure)
* [Nelson: Rigorous Deployment for a Functional World](https://youtu.be/3EHtAA4oE0k) - Timothy Perrett, Scale by the Bay 2017 - [slides](https://www.slideshare.net/timperrett/nelson-rigorous-deployment-for-a-functional-world)
* [Large Scale Infrastructure Automation at Verizon](https://youtu.be/RzmpW5a1zEI) - Timothy Perrett, HashiConf 2016 - [slides](https://www.slideshare.net/timperrett/largescale-infrastructure-automation-at-verizon-65797198)
* [Nelson: Multiregional container orchestration for Hashicorp Nomad and Lyft Envoy](https://youtu.be/lTIvKZHedJQ) - Timothy Perrett, San Francisco Infrastructure as Code Meetup June 2017 - [slides](https://www.slideshare.net/timperrett/nelson-automated-multiregion-container-deployment)
