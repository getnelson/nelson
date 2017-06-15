# Glossary

** WORK IN PROGRESS, TERMS SUBJECT TO CHANGE **

- *Environment*: A partition of data.
  Examples:
  * Dev
  * QA
  * Prod
   
- *Namespace*: a name + a list of Seed *ServiceNames*
  coupled to an *Environment*

- *ServiceType*: the name of a service, such as Maestro or SU

- *ServiceName*: A *ServiceType* coupled with a Major and Minor
  version numbers, for example: "Maestro_1.0"

- *StackName*: A named service deployment. It is a *ServiceType* with
  a hashcode and a version number, for example
  "Maestro_1.0.2_1AB8EF67"

- *service.yml*: A file checked into a service's source repository
  which includes the *ServiceType* and a list of named downstream *ServiceNames*

```yaml
- name: accounts
  dependencies:
    foo: foo_1.0
	bar: bar_2.1
```
  The above definition declares a service named "accounts" which talks to foo and bar services, and specifies the major / minor version of each depedency

- *Experiement*: a way of dynamincally changing the codepath a transaction would take

- *Experiement Group*: named list of experiements

- *Experiement Token*: A serialized list of instructions for changing the runtime codepath a transaction might take.

- *Dependency Graph*: A graph created by Nelson which will show the call dependencies between StackNames.

- *Routing Table*: A tree in consul which for all services deployed to
  a namesspace, assigns weights to possible target. This tree will be
  created by Nelson, Nelson will routinely rewrite this routing
  table. This table will be used to materialize haproxy configurations
  on each box which will route calls from a source service to a target
  service.

``` yaml
- namespace: prod
  services:
    accounts_1.2.3_prod_1AB8EF67:
      foo: 
	    foo_1.2.1_prod_F8B43ED4: .4
	    foo_1.2.1_prod_D98ED665: .6
      bar: 
	    bar_2.1.1_prod_F8B43ED4: 1

    foo_1.2.1_prod_F8B43ED4:
    foo_1.2.1_prod_D98ED665:
    bar_2.1.1_prod_F8B43ED4:
```

  This routing table includes 4 deployments of 3 different service
  types (accounts, foo, bar). Accounts is configured to route to two
  different services, foo and bar. Foo is being routed to two
  different deployments, with a 40%/60% split of traffic.

  These routing tables will be consumed by consul-template in order to
  create an haproxy configuration on each slave such that if
  accounts_1.2.3_prod_1AB8EF67 wants to call the getWidget verb on the
  foo service, it would hit a URL such as: `http://localhost/prod/accounts/foo/getWidget`.
  
  
