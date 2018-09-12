---
layout: "single"
toc: "false"
title: REST API
preamble: >
  The Nelson service provides a fairly extensive HTTP API. Most endpoints accept and return `application/json` representations. As the API is quite extensive (every action Nelson takes has an API), this document has been broken down into a set of logical sections outlined below:
contents:
- Overview
- Auditing
- Datacenters
- Load Balancers
- Misc
- Repositories
- Stacks
- Units
- Webhooks
menu:
  main:
    identifier: docs-api
    parent: docs
    url: /documentation/api.html
    weight: 6
---

## Overview

This section describes some common themes in the Nelson API.

<ol>
  <li><a href="#api-overview-authentication">Authentication</a></li>
  <li><a href="#api-overview-user-agents">User Agent Required</a></li>
  <li><a href="#api-overview-errors">Error Reporting</a></li>
</ol>

<h3 id="api-overview-authentication" class="linkable">
  Authentication
</h3>

All requests to Nelson are secured. The expectation is that every request includes the Nelson security token. The following is an example:

```
curl -H Cookie: nelson.session=XXXXXXXXXXXXXXXXXXXXXXXXXXXX https://your.domain.com/v1/
```

The cookie can be obtained via two of methods. The typical user path will be via the OAuth workflow, authenticating with the backend Github endpoint (Github Enterprise or github.com). In this case, the browser automatically collects the token and stores it in the browser cache.

For programatic API access, the user needs to supply a Github [personal access token](https://help.github.com/articles/creating-an-access-token-for-command-line-use/) to Nelson, which will then be exchanged for a Nelson security token.

```
POST /auth/github
```

<table class="table">
  <thead>
    <tr>
      <td><strong>Name</strong></td>
      <td><strong>Type</strong></td>
      <td><strong>Description</strong></td>
    </tr>
  </thead>
  <tbody>
    <tr>
      <td><code>access_token</code></td>
      <td><code>string</code></td>
      <td>The personal access token obtained from the aforementioned Github instance</td>
    </tr>
  </tbody>
</table>

<h3 id="api-overview-user-agents" class="linkable">
  User Agents
</h3>

Nelson restricts the `User-Agent` of callers, so every request sent to Nelson must supply a `User-Agent`. The server configuration details a set of banned user-agent strings, which is used as a mechanism to force clients to upgrade over time.

The following is an example.

```
User-Agent: NelsonCLI/1.0
```

If you provide an invalid or restricted User-Agent header, you will recieve a `403 Forbidden` response.

<h3 id="api-overview-errors" class="linkable">
  Error Reporting
</h3>

Nelson endpoints attempt to be good HTTP citizens and return appropriate 4xx status codes when the calling client is at fault, and only falls back to 5xx errors when the service itself was at fault.

<table class="table table-striped">
  <thead>
    <tr>
      <td width="18%"><strong>Code</strong></td>
      <td><strong>Description</strong></td>
    </tr>
  </thead>
  <tbody>
    <tr>
      <td><code>400 Bad Request</code></td>
      <td>Returned if a request body could not be parsed.  For instance, if a POST request contains ill-formed JSON, the following would be returned.
      <br />
      <br />
      <pre><code>{"message":"Could not parse JSON"}</code></pre>
      </td>
    </tr>
    <tr>
      <td><code>415 Unsupported Media Type</code></td>
      <td>Returned if a request did not send an acceptable media type.Requests with a JSON body should send a <code>Content-Type: application/json</code> header.
      <br />
      <br />
      <pre><code>{"message":"No media type specified in Content-Type header. Expected one of the following media ranges: application/json"}</code></pre>
      </td>
    </tr>
    <tr>
      <td><code>422 Unprocessable Entity</code></td>
      <td>Returned if a request body could be parsed, but not decoded, due to a missing or mistyped field.  The error returns a <code>message</code> and a <code>cursor_history</code> field from the JSON decoder.  This response is for a request that's missing an `access_token`:
      <br />
      <br />
      <pre><code>{
  "message": "Validation failed",
  "cursor_history": "CursorHistory([*.--\\(access_token)])"
}</code></pre>
      </td>
    </tr>
    <tr>
      <td><code>500 Internal Server Error</code></td>
      <td>The service encountered an unexpected exception whilst processing a request. This was not expected, and information is reported to the server log, but not reported in the response for security reasons.
      <br />
      <br />
      <pre><code>{"message":"An internal error occurred"}</code></pre>
      </td>
    </tr>
  </tbody>
</table>

<hr />

## Auditing

<ol>
  <li><a href="#api-audit-list">List Events</a></li>
</ol>

List all the audit events, matching the query parameters (if supplied).

<h3 id="api-audit-list" class="linkable">
  List Events
</h3>

```
GET /v1/audit
```

<h5>Paramaters</h5>

<table class="table">
  <thead>
    <tr>
      <td><strong>Name</strong></td>
      <td><strong>Type</strong></td>
      <td><strong>Required</strong></td>
      <td><strong>Description</strong></td>
    </tr>
  </thead>
  <tbody>
    <tr>
      <td><code>release_id</code></td>
      <td><code>int</code></td>
      <td>No</td>
      <td>The release ID that you specifically want to fetch audit events for</td>
    </tr>
    <tr>
      <td><code>limit</code></td>
      <td><code>int</code></td>
      <td>No</td>
      <td>The maximum number of events to return in a given response</td>
    </tr>
    <tr>
      <td><code>offset</code></td>
      <td><code>int</code></td>
      <td>No</td>
      <td>Where to start reading events from in the audit stream</td>
    </tr>
  </tbody>
</table>

<hr />

## Datacenters

<ol>
  <li><a href="#api-datacenters-list">List Datacenters</a></li>
  <li><a href="#api-datacenters-inspect">Inspect Datacenter</a></li>
  <li><a href="#api-datacenters-list-ns">Create Namespaces</a></li>
</ol>

<h3 id="api-datacenters-list" class="linkable">
  List Datacenters
</h3>

```
GET /v1/datacenters
```

<h5>Response</h5>

```
[
  {
    "namespaces": [
      {
        "id": 3,
        "name": "dev",
        "units_url": "https://nelson.domain.com/v1/units?dc=massachusetts&status=active,manual,deprecated",
        "deployments_url": "https://nelson.domain.com/v1/deployments?dc=massachusetts&ns=dev",
      }
    ],
    "datacenter_url": "https://nelson.domain.com/v1/datacenters/massachusetts",
    "name": "massachusetts"
  },
  {
    "namespaces": [
      {
        "id": 4,
        "name": "dev",
        "units_url": "https://nelson.domain.com/v1/units?dc=texas&status=active,manual,deprecated",
        "deployments_url": "https://nelson.domain.com/v1/deployments?dc=texas&ns=dev",
      }
    ],
    "datacenter_url": "https://nelson.domain.com/v1/datacenters/texas",
    "name": "texas"
  }
]
```

<h3 id="api-datacenters-inspect" class="linkable">
  Inspect Datacenter
</h3>

```
GET /v1/datacenters/:dcname
```

<h5>Response</h5>

```
{
  "namespaces": [
    {
      "id": 4,
      "name": "dev",
      "units_url": "https://nelson.domain.com/v1/units?dc=texas&status=active,manual,deprecated",
      "deployments_url": "https://nelson.domain.com/v1/deployments?dc=texas&ns=dev",
    }
  ],
  "datacenter_url": "https://nelson.domain.com/v1/datacenters/texas",
  "name": "texas"
}
```

<h3 id="api-datacenters-list-ns" class="linkable">
  Create Namespaces
</h3>

```
POST /v1/datacenters/:dcname/namespaces
```

<h5>Input</h5>

<table class="table">
  <thead>
    <tr>
      <td><strong>Name</strong></td>
      <td><strong>Type</strong></td>
      <td><strong>Required</strong></td>
      <td><strong>Description</strong></td>
    </tr>
  </thead>
  <tbody>
    <tr>
      <td><code>namespaces</code></td>
      <td><code>array[string]</code></td>
      <td>Yes</td>
      <td>A list of namespace labels</td>
    </tr>
  </tbody>
</table>

<h5>Example</h5>

```
{
  "namespaces": [
    "dev",
    "stage",
    "prod"
  ]
}
```

<h5>Response</h5>

Successful completion of this operation yields a `200` with empty response body.

<hr />

## Load Balancers

<ol>
  <li><a href="#api-lbs-list">List Load Balancers</a></li>
  <li><a href="#api-lbs-inspect">Inspect Load Balancers</a></li>
  <li><a href="#api-lbs-up">Create Load Balancer</a></li>
  <li><a href="#api-lbs-down">Destroy Load Balancer</a></li>
</ol>

<h3 id="api-lbs-list" class="linkable">
  List Load Balancers
</h3>

Listing the available load balancers in a given namespace displays all the associated routes for that load balancers, and optionally (depending on the load balancer backend in use), the externally accessible URL.

```
GET /v1/loadbalancers
```

##### Response

```
[
  {
    "name": "foobar-lb--1--6i07ecdr",
    "routes": [
      {
        "backend_port_reference": "default",
        "backend_name": "foobar-http",
        "lb_port": 8444
      }
    ],
    "guid": "e2e09310d926",
    "deploy_time": 1483739055573,
    "address": "foobar-lb--1--6i07ecdr-1888907488.texas.elb.amazonaws.com",
    "datacenter": "texas",
    "namespace": "dev",
    "major_version": 1
  },
  ...
]
```

<h3 id="api-lbs-inspect" class="linkable">
  Inspect Load Balancer
</h3>

```
GET /v1/loadbalancers/:guid
```

##### Response

```
{
  "name": "foobar-lb--1--6i07ecdr",
  "routes": [
    {
      "backend_port_reference": "default",
      "backend_name": "foobar-http",
      "lb_port": 8444
    }
  ],
  "guid": "e2e09310d926",
  "deploy_time": 1483739055573,
  "address": "foobar-lb--1--6i07ecdr-1888907488.texas.elb.amazonaws.com",
  "datacenter": "texas",
  "namespace": "dev",
  "major_version": 1
}
```

<h3 id="api-lbs-up" class="linkable">
  Create Load Balancer
</h3>

<div class="alert alert-warning" role="alert">
This API is only available to Nelson administrators.
</div>

```
POST /v1/loadbalancers
```

##### Request

```
{
  "name": "foobar-lb",
  "major_version": 1,
  "datacenter": "texas",
  "namespace": "dev"
}
```

##### Response

Successful completion of this operation yields a `200` with empty response body.

<h3 id="api-lbs-down" class="linkable">
  Destroy Load Balancer
</h3>

<div class="alert alert-warning" role="alert">
This API is only available to Nelson administrators.
</div>

```
DELETE /v1/loadbalancers/:guid
```

##### Response

Successful completion of this operation yields a `200` with empty response body.

<hr />

## Misc

<ol>
  <li><a href="#api-misc-sync">Sync User Profile</a></li>
  <li><a href="#api-misc-lint-manifest">Lint Manifest</a></li>
  <li><a href="#api-misc-lint-template">Lint Template</a></li>
  <li><a href="#api-misc-cleanup-policies">List Cleanup Policies</a></li>
</ol>

<h3 id="api-misc-sync" class="linkable">
  Sync User Profile
</h3>

`POST`ing to this URL will result in Nelson fetching the latest list of repositories from Github and updating this users permission set. Typically this API is used by the user-interface, but might need to be called when a new repository was created that the user wishes to enable deployment for.

```
POST /v1/profile/sync
```

<h3 id="api-misc-lint-manifest" class="linkable">
  Lint Manifest
</h3>

The service can verify if a supplied manifest is valid or not (e.g. if the dependencies specified in the file dont exist, validation will fail).

```
POST /v1/lint
```

<h5>Input</h5>

<table class="table">
  <thead>
    <tr>
      <td><strong>Name</strong></td>
      <td><strong>Type</strong></td>
      <td><strong>Required</strong></td>
      <td><strong>Description</strong></td>
    </tr>
  </thead>
  <tbody>
    <tr>
      <td><code>units</code></td>
      <td><code>array</code></td>
      <td>Yes</td>
      <td>List of informational objects about the available units</td>
    </tr>
    <tr>
      <td><code>manifest</code></td>
      <td><code>string</code></td>
      <td>Yes</td>
      <td>Base64 encoded string representation of the .nelson.yml file</td>
    </tr>
  </tbody>
</table>

<h5>Example</h5>

```
{
  "units": [
    {
      "kind": "foo"
      "name": "foo-1.0"
    }
  ],
  "manifest": "XXXXXXXX"
}
```

<h5>Response</h5>

<h3 id="api-misc-lint-template" class="linkable">
  Lint Template
</h3>

The service can verify if a supplied Consul template will render or not in the container.  A template is uploaded with the name of its unit and a set of resources.  The template is rendered with a vault token appropriate to the unit and resources, along with all the `NELSON_` environment variables. If the template renders successfully, a successful status is returned.  If the template can't be rendered, the errors are displayed so the developer can fix them before deploying the unit.

```
POST /v1/validate-template
```

<h5>Input</h5>

<table class="table">
  <thead>
    <tr>
      <td><strong>Name</strong></td>
      <td><strong>Type</strong></td>
      <td><strong>Required</strong></td>
      <td><strong>Description</strong></td>
    </tr>
  </thead>
  <tbody>
    <tr>
      <td><code>unit</code></td>
      <td><code>string</code></td>
      <td>Yes</td>
      <td>Name of the unit owning the template. Units will be validated in dev against the policy for this unit.</td>
    </tr>
    <tr>
      <td><code>resources</code></td>
      <td><code>array</code></td>
      <td>Yes</td>
      <td>An array of resources accessed by the unit. Access will be granted to these resources in the policy.</td>
    </tr>
    <tr>
      <td><code>template</code></td>
      <td><code>string</code></td>
      <td>Yes</td>
      <td>Base64 encoded string representation of the template to render</td>
    </tr>
  </tbody>
</table>

<h5>Example</h5>

```
{
  "unit": "howdy-http",
  "resources": ["s3"],
  "template": "SGVsbG8sIGNvbnN1bC10ZW1wbGF0ZQo="
}
```

<h5>Response</h5>

On success, a 204.  The rendered template output is suppressed for security reasons.

On failure, a 400 with `details` and `message`:

```
{
    "details": "2017/02/06 20:25:47.495957 [INFO] consul-template v0.18.0 (5211c66)\n2017/02/06 20:25:47.495969 [INFO] (runner) creating new runner (dry: true, once: true)\n2017/02/06 20:25:47.496155 [INFO] (runner) creating watcher\n2017/02/06 20:25:47.500011 [INFO] (runner) starting\n2017/02/06 20:25:47.500083 [INFO] (runner) initiating run\nConsul Template returned errors:\n/consul-template/templates/nelson2613876961835551321.template: parse: template: :3: unterminated quoted string\n",
    "message": "template rendering failed"
}
```

<h3 id="api-misc-cleanup-policies" class="linkable">
  List Cleanup Policies
</h3>

List the available cleanup policies available in this instance of Nelson

```
GET /v1/cleanup-policies
```

<h5>Response</h5>

```
[
  {
    "description": "retains forever, or until someone manually decommissions",
    "policy": "retain-always"
  },
  ...
]
```

<hr />

## Repositories

<ol>
  <li><a href="#api-repos-list">List Repositories</a></li>
  <li><a href="#api-repos-list-releases">List Repository Releases</a></li>
  <li><a href="#api-repos-list-all-releases">List All Releases</a></li>
  <li><a href="#api-repos-inspect-release">Inspect Release</a></li>
  <li><a href="#api-repos-enable">Enable Repository</a></li>
  <li><a href="#api-repos-disable">Disable Repository</a></li>
</ol>


<h3 id="api-repos-list" class="linkable">
  List Repositories
</h3>

```
GET /v1/repos
GET /v1/repos?owner=tim
GET /v1/repos?state=active
```

<h5>Paramaters</h5>

<table class="table">
  <thead>
    <tr>
      <td><strong>Name</strong></td>
      <td><strong>Type</strong></td>
      <td><strong>Required</strong></td>
      <td><strong>Description</strong></td>
    </tr>
  </thead>
  <tbody>
    <tr>
      <td><code>owner</code></td>
      <td><code>string</code></td>
      <td>No</td>
      <td>List only repositories with the specified owner</td>
    </tr>
    <tr>
      <td><code>manifest</code></td>
      <td><code>string</code></td>
      <td>state</td>
      <td>List only repositories with the specified state; options are: <code>active</code></td>
    </tr>
  </tbody>
</table>

<h5>Response</h5>

```
[
  {
    "repository": "foobar",
    "slug": "tim/foobar",
    "id": 6124,
    "hook": null,
    "owner": "tim",
    "access": "admin"
  },
  ...
]
```

<h3 id="api-repos-list-releases" class="linkable">
  List Repository Releases
</h3>

```
GET /v1/repos/:org/:repo/releases
```

<h5>Response</h5>

```
[
  {
    "timestamp": "2016-06-28T20:01:40.091Z",
    "release_url": "https://nelson.example.com/v1/releases/990",
    "slug": "example/howdy",
    "github_html_url": "https://github.com/example/howdy/releases/tag/0.38.131",
    "version": "0.38.131",
    "id": 990,
    "deployments": [
      {
        "name": "howdy-http",
        "timestamp": "2016-06-28T20:02:26.810Z",
        "description": "provides the server component of the 'howdy' demo",
        "unit_id": 2,
        "deployment_url": "https://nelson.example.com/v1/deployments/2aa6e4c0295d",
        "hash": "i5hbv0bd",
        "id": 3,
        "status": "deploying",
        "kind": "service",
        "dependencies": []
      },
      {
        "name": "howdy-batch",
        "timestamp": "2016-06-28T20:01:40.370Z",
        "description": "example batch job\n",
        "unit_id": 3,
        "deployment_url": "https://nelson.example.com/v1/deployments/5555d15b54a4",
        "hash": "o6sbrepj",
        "id": 2,
        "status": "deploying",
        "kind": "job",
        "dependencies": []
      }
    ]
  },
  ...
]
```

<h3 id="api-repos-list-all-releases" class="linkable">
  List All Releases
</h3>

```
GET /v1/releases
```

<h3 id="api-repos-inspect-release" class="linkable">
  Inspect Release
</h3>

```
GET /v1/releases/:id
```

<h3 id="api-repos-enable" class="linkable">
  Enable Repository
</h3>

```
POST /v1/repos/:org/:repo/hook
```

<h3 id="api-repos-disable" class="linkable">
  Disable Repository
</h3>

```
DELETE /v1/repos/:org/:repo/hook
```

<hr />

## Stacks

<ol>
  <li><a href="#api-stacks-list">List Stacks</a></li>
  <li><a href="#api-stacks-inspect">Inspect Stack</a></li>
  <li><a href="#api-stacks-create-manual">Create Manually</a></li>
  <li><a href="#api-stacks-redeploy">Redeploy</a></li>
  <li><a href="#api-stacks-logs">Inspect Logs</a></li>
</ol>

<h3 id="api-stacks-list" class="linkable">
  List Stacks
</h3>

```
GET /v1/deployments
```

<h5>Paramaters</h5>

<table class="table">
  <thead>
    <tr>
      <td><strong>Name</strong></td>
      <td><strong>Type</strong></td>
      <td><strong>Required</strong></td>
      <td><strong>Description</strong></td>
    </tr>
  </thead>
  <tbody>
    <tr>
      <td><code>namespace</code></td>
      <td><code>string[,string,...]</code></td>
      <td>Yes</td>
      <td>Comma delimited set of namespace(s) you want to list deployments for</td>
    </tr>
    <tr>
      <td><code>status</code></td>
      <td><code>string[,string,...]</code></td>
      <td>No</td>
      <td>Comma delimited set of <code>status</code> values (e.g. <code>active</code>,<code>deploying</code>)</td>
    </tr>
    <tr>
      <td><code>dc</code></td>
      <td><code>string[,string,...]</code></td>
      <td>No</td>
      <td>Comma delimited set of datacenter names the result set include</td>
    </tr>
    <tr>
      <td><code>unit</code></td>
      <td><code>string</code></td>
      <td>No</td>
      <td>Unit name</td>
    </tr>
  </tbody>
</table>

<h5>Response</h5>

```
[
  {
    "workflow": "magnetar",
    "guid": "b5280a26c026",
    "stack_name": "howdy-batch--0-38-155--aclq8j4j",
    "deployed_at": 1470784352740,
    "unit": "howdy-batch",
    "datacenter": "texas",
    "namespace": "dev",
    "plan": "dev-plan"
  },
  {
    "workflow": "magnetar",
    "guid": "072bdf0b99e8",
    "stack_name": "howdy-batch--0-38-155--5mrkdta9",
    "deployed_at": 1470784281154,
    "unit": "howdy-batch",
    "datacenter": "massachusetts",
    "namespace": "dev",
    "plan": "dev-plan"
  }
]
```
<h3 id="api-stacks-inspect" class="linkable">
  Inspect Stack
</h3>

```
GET /v1/deployments/:guid
```

<h5>Response</h5>

```
{
  "workflow": "magnetar",
  "guid": "072bdf0b99e8",
  "statuses": [
    {
      "timestamp": "2016-08-09T23:12:07.267Z",
      "message": "howdy-batch deployed to massachusetts",
      "status": "active"
    },
    {
      "timestamp": "2016-08-09T23:12:06.727Z",
      "message": "instructing massachusetts's nomad to handle job container",
      "status": "deploying"
    },
    {
      "timestamp": "2016-08-09T23:12:06.063Z",
      "message": "writing alert definitions to massachusetts's consul",
      "status": "deploying"
    },
    {
      "timestamp": "2016-08-09T23:11:21.295Z",
      "message": "replicating docker.yourcompany.com/units/howdy-batch-0.38:0.38.155 to remote registry",
      "status": "deploying"
    },
    {
      "timestamp": "2016-08-09T23:11:21.243Z",
      "message": "",
      "status": "pending"
    }
  ],
  "stack_name": "howdy-batch--0-38-155--5mrkdta9",
  "deployed_at": 1470784281154,
  "unit": "howdy-batch",
  "expiration": 1470902303320,
  "dependencies": {
    "outbound": [],
    "inbound": []
  },
  "namespace": "dev",
  "plan": "dev-plan"
}
```


<h3 id="api-stacks-create-manual" class="linkable">
  Create Manually
</h3>

<div class="alert alert-warning" role="alert">
This API is only available to Nelson administrators.
</div>

```
POST /v1/deployments
```

<h5>Input</h5>

<table class="table">
  <thead>
    <tr>
      <td><strong>Name</strong></td>
      <td><strong>Type</strong></td>
      <td><strong>Required</strong></td>
      <td><strong>Description</strong></td>
    </tr>
  </thead>
  <tbody>
    <tr>
      <td><code>datacenter</code></td>
      <td><code>string</code></td>
      <td>Yes</td>
      <td>TBD</td>
    </tr>
    <tr>
      <td><code>namespace</code></td>
      <td><code>string</code></td>
      <td>Yes</td>
      <td>TBD</td>
    </tr>
    <tr>
      <td><code>service_type</code></td>
      <td><code>string</code></td>
      <td>Yes</td>
      <td>TBD</td>
    </tr>
    <tr>
      <td><code>version</code></td>
      <td><code>string</code></td>
      <td>Yes</td>
      <td>TBD</td>
    </tr>
    <tr>
      <td><code>hash</code></td>
      <td><code>string</code></td>
      <td>Yes</td>
      <td>TBD</td>
    </tr>
    <tr>
      <td><code>description</code></td>
      <td><code>string</code></td>
      <td>Yes</td>
      <td>TBD</td>
    </tr>
    <tr>
      <td><code>port</code></td>
      <td><code>int</code></td>
      <td>Yes</td>
      <td>TBD</td>
    </tr>
  </tbody>
</table>

<h3 id="api-stacks-redeploy" class="linkable">
  Redeploy
</h3>

Sometimes it is necessary to redeploy an exist stack. In this event, simply `POST` an empty body to the following API:

```
POST /v1/deployments/:guid/redeploy
```

<h3 id="api-stacks-reverse" class="linkable">
  Redeploy
</h3>

Sometimes it is necessary to reverse and in progress traffic shift. In this event, simply `POST` an empty body to the following API:

```
POST /v1/deployments/:guid/trafficshift/reverse
```


<h3 id="api-stacks-logs" class="linkable">
  Inspect Logs
</h3>

Collect the entire log of workflow execution as Nelson processed it, for a given stack deployment. The workflow log allows the caller to see exactly what Nelson did on the users behalf, without requiring access to the Nelson server. The response includes every log line, and an `offset` that allows the caller to only receive a subset of the log output.

```
GET /v1/deployments/:guid/logs
```

<h5>Response</h5>

```
{
  "content": [
    "deployment initialized",
    "",
    "container extracted docker.yourcompany.com/units/heydiddlyho-http-0.33",
    ...
    "======> workflow completed <======",
    "decommissioning deployment heydiddlyho-http--0-33-69--56rk8kv0 in massachusetts"
  ],
  "offset": 0
}
```

<hr />

## Units

Units blah blah blah blah blah blah blah blah blah blah blah

<ol>
  <li><a href="#api-units-list">List Units</a></li>
  <li><a href="#api-units-commit">Commit Units</a></li>
  <li><a href="#api-units-deprecate">Deprecate Unit</a></li>
</ol>

<h3 id="api-units-list" class="linkable">
  List Units
</h3>

```
GET /v1/units
```
<h3 id="api-units-commit" class="linkable">
  Commit Units
</h3>

Commits a `unit@version` combination to a target namespace. See [Committing](#manifest-namespace-sequencing) for more.

```
POST /v1/units/commit

{
  "unit": "unit-name",
  "version": "1.9.32",
  "target": "qa"
}
```

<h3 id="api-units-deprecate" class="linkable">
  Deprecate Unit
</h3>

```
POST /v1/units/deprecate
```

<hr />

## Webhooks

<ol>
  <li><a href="#api-webhooks-github">Github Listener</a></li>
</ol>

<h3 id="api-webhooks-github" class="linkable">
  Github Listener
</h3>

```
POST /listener
```
