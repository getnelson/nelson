---
layout: "single"
title: "Competitors"
preamble: >
  Nelson is in many ways a uniuqe system; the principles and methods used in its construction are not common in the operations domain. However, is is common for potential users to draw parrallels with other systems, and navigating the competitive space can often be difficult. The systems most commonly compared with Nelson are enumerated below a brief comparitive explanation is provided to help understand Nelson's value proposistion.
contents:
- Spinnaker
- Collins
menu:
  main:
    parent: 'intro'
    identifier: intro-competitors
    url: /introduction/competitors.html
    weight: 3
---

## Spinnaker

One of the most common systems Nelson is compared to is the [Spinnaker project](https://spinnaker.io). On the face of it, the systems are indeed solving similar problems. The truth is however a little more nuanced. Spinnaker was born out of Netflix as a replacement for their [Asguard deployment system](https://github.com/Netflix/asgard). The primary difference between Spinnaker and Nelson is that Nelson is container-first where as Spinnaker was primarily designed to operate with Netflix's virtual machine "bakery". Whilst they have since retrofitted support for containers, the approach of the project cannot detech itself from that legacy workflow easily. Given Nelson is focused on integration with scheduling systems as its common substrate for deployment, the management of the virtual machines underlying those schedulers is out of scope. Indeed, with the prevelance of hosted scheudling systems like [EKS](https://aws.amazon.com/eks/), [EKS](https://cloud.google.com/kubernetes-engine/) and [AKE](https://azure.microsoft.com/en-us/services/kubernetes-service/), the need for even running your own machines appears to be passing.

Spinnaker has support for a wide range of process workflows (for example, gating deployments on manual intervention). Broadly speaking, Nelson is strongly against this from a philosophical standpoint, as such manual processes are a crutch for a lack of automation of continuous testing. Nelson by comparison provides a foundation for a robust experimentation system, where testing in production is the norm - this embraces the reality of the world we live in: ship frequently, experiment often, observe everything. The underlying intent here is that Nelson in no way perscribes how you will operate your release management; it simply provides a set of primitives.

## Collins

The [Collins](https://tumblr.github.io/collins/) configuration management system - along with other configuration management solutions - do not truley embrace immutable infrastructure. Typically these systems are focused on maintaining a static fleet of servers and evolving them over time, applying patches and so forth. This is an intrinsically different model to the one Nelson provides. Collins would be a better fit for teams that which to manage the underlying infrastructure a scheduler might run on, instead of end-user applications.
