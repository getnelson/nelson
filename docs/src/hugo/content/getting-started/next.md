---
layout: "single"
title: "Further Reading"
preamble: ""
menu:
  main:
    parent: gs
    identifier: gs-next
    url: /getting-started/next.html
    weight: 6
---

In conclusion, the manifest contains a variety of settings and options too numerous to mention in this introductory text. Suggested further reading [in the reference](reference.html#manifest) about the Nelson manifest answers the following common queries:

* [How do I declare a dependency on another unit?](/documentation/manifest.html#manifest-unit-dependencies)
* [How do I get alerted when my unit has a problem at runtime?](/documentation/manifest.html#manifest-unit-alerting)
* [How do I expose my service to the outside world?](/documentation/manifest.html#manifest-load-balancers)

Now that you have your `.nelson.yml` as you want it, add the file to the **root** of your source repository, and commit it to the `master` branch. Nelson will look at the repositories' `master` branch when it first attempts to validate your repository is something that is Nelson-compatible. You can check the validity of your manifest definition at anytime without checking in by using the Nelson CLI: `nelson lint manifest`.