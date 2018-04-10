
+++
layout = "single"
title  = "Reference"
weight = 1
menu   = "main"
+++

<h1 id="api" class="page-header">REST API</h1>

The Nelson service provides a fairly extensive HTTP API. Most endpoints accept and return `application/json` representations.

<h2 id="api-overview" data-subheading-of="api">Overview</h2>

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

<h2 id="api-audit" data-subheading-of="api">Auditing</h2>

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


<h2 id="api-datacenters" data-subheading-of="api">Datacenters</h2>

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

<h2 id="api-lbs" data-subheading-of="api">Load Balancers</h2>

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

<h2 id="api-misc" data-subheading-of="api">Misc</h2>

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

<h2 id="api-repos" data-subheading-of="api">Repositories</h2>

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

<h2 id="api-stacks" data-subheading-of="api">Stacks</h2>

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


<h2 id="api-units" data-subheading-of="api">Units</h2>

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

Commits a unit@version combination to a target namespace. See [Committing](#manifest-namespace-sequencing) for more.

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

<h2 id="api-webhooks" data-subheading-of="api">Webhooks</h2>

<ol>
  <li><a href="#api-webhooks-github">Github Listener</a></li>
</ol>

<h3 id="api-webhooks-github" class="linkable">
  Github Listener
</h3>

```
POST /listener
```

<h1 id="cli" class="page-header">Command Line</h1>

For installation instructions, see the [user guide](index.html#user-guide) section. This reference covers the set of operations supplied by the CLI.

<h2 id="cli-system" data-subheading-of="cli">System Operations</h2>

*Nelson* provides a set of system operations that allow you to get a set of informational data about your session

<h3 id="cli-system-whoami" class="linkable">
  Who Am I
</h3>

Who are you currently logged in as and to which server? Just ask! This is useful when you're not sure which remote backend you're talking too.

```
λ nelson whoami
===>> Currently logged in as Jane Doe @ https://nelson.example.com
```

<h3 id="cli-system-cleanup" class="linkable">
  Cleanup Policies
</h3>

When you need to know what cleanup policies are available on this instance of *Nelson*, run the following:

```
λ nelson system cleanup-policies
POLICY                     DESCRIPTION
retain-always              retains forever, or until someone manually decommissions
retain-latest              retains the latest version
retain-active              retains all active versions; active is an incoming dependency
retain-latest-two-feature  retains the latest two feature versions, i.e. 2.3.X and 2.2.X
retain-latest-two-major    retains the latest two major versions, i.e. 2.X.X and 1.X.X
```

<h3 id="cli-system-login" class="linkable">
  Login
</h3>

When logging into *Nelson*, there are a variety of options and ways in which to use the command line. Generally speaking, it is recommended that the Github PAT that you setup during the installation is set to the environment variables `GITHUB_TOKEN`, but if you don't want to do that, supplying it explicitly is also supported. Typical usage would look like this:

```
# read token from environment variable GITHUB_TOKEN, but supply explicit host
$ nelson login nelson.yourdomain.com
```

But the following additional options are also availabe. Note that the `--disable-tls` option should only ever be used for local development, and is not intended for regular usage.

```
# fully explicit login
λ nelson login --token 1f3f3f3f3 nelson.yourdomain.com

# read token from env var GITHUB_TOKEN and host from NELSON_ADDR
λ nelson login

# for testing with a local server, you can do:
λ nelson login --disable-tls --token 1f3f3f3f3 nelson.local:9000
```

<h2 id="cli-datacenter" data-subheading-of="cli">Datacenter Operations</h2>

The Nelson service would have been configured by your system administrator for use with one or more datacenters. These datacenters are your deployment targets.

```
# list the available nelson datacenters
λ nelson datacenters list
  DATACENTER      NAMESPACES
  massachusetts   dev
  texas           dev

# just an alias for the above
λ nelson dcs list
```

Note how the resulting list enumerates the namespaces available in that datacenter. In the event multiple namespaces are available, they will be collapsed into a comma-delimited list.

<h2 id="cli-unit" data-subheading-of="cli">Unit Operations</h2>

As a `unit` in Nelson parlance is a logical concept, the client allows you to query the Nelson instance for units, regardless of the stack they are a part of.

<h3 id="cli-unit-listing" class="linkable">
  Listing
</h3>

This is typically very useful when building out (or upgrading) your own unit that depends on another unit, and you need to know what versions are currently available, or in a given state. The following is an example:

```
λ nelson units list --namespaces dev --datacenters massachusetts
  GUID          NAMESPACE  UNIT              VERSION
  a0d3e7843856  dev      heydiddlyho-http  0.33
  c0e4281e9c9d  dev      howdy-http        0.38
```

The `unit list` subcommand comes with a variety of switches and options, for your convenience:

<table class="table table-striped">
  <thead>
    <tr>
      <td><strong>Option</strong></td>
      <td><strong>Alias</strong></td>
      <td><strong>Required</strong></td>
      <td><strong>Default</strong></td>
      <td><strong>Description</strong></td>
    </tr>
  </thead>
  <tbody>
    <tr>
      <td width="17%"><code>--namespaces</code></td>
      <td width="8%"><code>-ns</code></td>
      <td>Yes</td>
      <td><code>n/a</code></td>
      <td>Specify the one or more namespaces that you wish to query. Accepts a command delimited list, for example: `dev,qa,prod`. Note that the comma-delimited list must not contain any spaces</td>
    </tr>
    <tr>
      <td><code>--datacenters</code></td>
      <td><code>-d</code></td>
      <td>No</td>
      <td><code>*</code></td>
      <td>Specify the one or more datacenters you're interested. It is excepted that users would typically not have to specify this switch, as usually users care about overall availability, not the presence of a given datacenter.</td>
    </tr>
    <tr>
      <td><code>--statuses</code></td>
      <td><code>-s</code></td>
      <td>No</td>
      <td><code>warming</code><br />
          <code>active</code><br/>
          <code>deprecated</code></td>
      <td>Specify the one or more statuses that you want the unit to be in. The valid options are <code>pending</code>, <code>deploying</code>, <code>warming</code>,<code>ready</code>, <code>deprecated</code>, <code>garbage</code>, <code>failed</code>, <code>terminated</code></td>
    </tr>
  </tbody>
</table>

These options can be combined or committed in a variety of ways:

```
# show the units deployed in a given datacenter
λ nelson units list --namespaces dev --datacenters massachusetts

# show the units available in several datacenters
λ nelson units list -ns dev --datacenters massachusetts,california

# show the units available in all datacenters for a given namespace
λ nelson units list --namespaces dev

# show the units available in all datacenters for a given namespace and status
λ nelson units list --namespaces dev --statuses deploying,active,deprecated

# show the units that have been terminated by nelson in a given namespace
λ nelson units list --namespaces dev --statues terminated
```

<h3 id="cli-unit-commit" class="linkable">
  Commit
</h3>

Commit a unit@version combination to a specific target namespace. The unit must be associated with the target namespace in the `namespaces` section of the manifest.

```
namespaces:
  - name: dev
    units:
      - ref: howdy-http
  - name: qa
    units:
      - ref: howdy-http
```

To promote version 0.38.145 of howdy-batch to qa, issue the following command:

```
# Commits unit howdy, version 0.38.145 to the qa namespace
λ nelson unit commit --unit howdy-http --version 0.38.145 --target qa
```

See [Committing](#manifest-namespace-sequencing) for more.

<h3 id="cli-unit-inspection" class="linkable">
  Inspection
</h3>

If you know the GUID of the unit you're interested in, you can actually get a much more detailed view of it, by using the `inspect` command.

<div class="alert alert-warning" role="alert">
⛔ Currently not implemented. Coming soon!
</div>

<h2 id="cli-stacks" data-subheading-of="cli">Stack Operations</h2>

The primary operation most users will want the CLI for is to find a stack that was recently deployed. With this in mind there are a variety of operations available for stacks.

<h3 id="cli-stacks-listing" class="linkable">
  Listing
</h3>

Just like listing units, listing stacks is super easy:

```
λ nelson stacks list --namespaces dev --datacenters massachusetts
  GUID          NAMESPACE  PLAN     STACK                            WORKFLOW  DEPLOYED AT
  d655dc6a5799  dev        dev-plan howdy-batch--0-38-145--eov7446m  default   2016-07-15T11:37:08-07:00
```

The `stack list` subcommand also comes with a variety of switches and options, for your convenience:

<table class="table table-striped">
  <thead>
    <tr>
      <td><strong>Option</strong></td>
      <td><strong>Alias</strong></td>
      <td><strong>Required</strong></td>
      <td><strong>Default</strong></td>
      <td><strong>Description</strong></td>
    </tr>
  </thead>
  <tbody>
    <tr>
      <td width="17%"><code>--namespaces</code></td>
      <td width="8%"><code>-ns</code></td>
      <td>Yes</td>
      <td><code>n/a</code></td>
      <td>Specify the one or more namespaces that you wish to query. Accepts a command delimited list, for example: `dev,qa,prod`. Note that the comma-delimited list must not contain any spaces</td>
    </tr>
    <tr>
      <td><code>--datacenters</code></td>
      <td><code>-d</code></td>
      <td>No</td>
      <td><code>*</code></td>
      <td>Specify the one or more datacenters you're interested in. It is expected that users would typically not have to specify this switch, as usually users care about overall availability, not the presence of a given datacenter.</td>
    </tr>
    <tr>
      <td><code>--statuses</code></td>
      <td><code>-s</code></td>
      <td>No</td>
      <td><code>warming</code><br />
          <code>ready</code><br/>
          <code>deprecated</code></td>
      <td>Specify the one or more statuses that you want the returned stacks to be in. The valid options are <code>pending</code>, <code>deploying</code>, <code>warming</code>,<code>ready</code>, <code>deprecated</code>, <code>garbage</code>, <code>failed</code>, <code>terminated</code>, any of which can be supplied, in any order.</td>
    </tr>
  </tbody>
</table>

These options can be combined or committed in a variety of ways:

```
# show the stacks deployed in a given datacenter
λ nelson stacks list --namespaces dev --datacenters massachusetts

# show the stacks availabe in several datacenters
λ nelson stacks list --namespaces dev --datacenters massachusetts,california

# show the stacks available in all datacenters for a given namespace
λ nelson stacks list --namespaces dev

# show the stacks available in all datacenters for a given namespace and status
λ nelson stacks list --namespaces dev --statuses deploying,ready,deprecated

# show the stacks that have been terminated by nelson in a given namespace
λ nelson stacks list --namespaces dev --statues terminated

```

<h3 id="cli-stacks-inspection" class="linkable">
  Inspection
</h3>

If you know the GUID of the stack you're interested in, you can get a much more detailed view of it, by using the `inspect` command. The following is an example:

```
λ nelson stacks inspect ec695f081fbd
===>> Stack Information
  PARAMATER     VALUE
  GUID:         ec695f081fbd
  STACK NAME:   search-http--1-0-28--t82nitd5
  NAMESPACE:    dev
  PLAN:         dev-plan
  WORKFLOW:     magnetar
  DEPLOYED AT:  2016-07-14T08:39:51-07:00
  EXPIRES AT:   2016-08-05T12:11:49-07:00

===>> Dependencies
  GUID          STACK                            WORKFLOW  DEPLOYED AT                DIRECTION
  f8a6d4f03bf3  es-search--1-7-5--58e6ad5e8cf2   default   2016-07-14T08:00:55-07:00  OUTBOUND

===>> Status History
  STATUS     TIMESTAMP                 MESSAGE
  active     2016-07-14T15:41:13.113Z  search-http deployed to massachusetts
  deploying  2016-07-14T15:41:13.068Z  writing alert definitions to massachusetts's consul
  deploying  2016-07-14T15:41:13.021Z  waiting for the application to become ready
  deploying  2016-07-14T15:41:05.089Z  instructing massachusetts's marathon to launch container
  deploying  2016-07-14T15:39:52.077Z  replicating search-http-1.0:1.0.28 to remote registry
  pending    2016-07-14T15:39:52.018Z
```

As you can see, this is significantly more information than the listing table. Whilst the stack information gets you mostly what you knew from the listing table, the dependencies section shows you both who this stack depends on, and also which other stacks depend on this stack. The Status History section shows a summary of the discrete steps the deployment went through and the times between each step.

<h3 id="cli-stacks-logs" class="linkable">
  Logs
</h3>

During deployment, it is frequently useful to see exactly what Nelson was doing when it executed the deployment workflow. For this, we provide the logs command.

```
λ nelson stacks logs f81c7a504bf6
===>> logs for stack f81c7a504bf6
2016-12-20T23:18:56.692Z: workflow about to start
2016-12-20T23:18:56.748Z: replicating dockerstg.yourcompany.com/units/howdy-http-1.0:1.0.348 to remote registry docker.dc1.yourcompany.com/units
2016-12-20T23:19:03.648Z: 02960cb05ba7 Pulling fs layer
[...]
2016-12-20T23:19:03.661Z: 86c2e42552a1 Pull complete
[...]
2016-12-20T23:21:26.528Z: 428087232e7e Pushed
2016-12-20T23:21:26.529Z: writing alert definitions to texas's consul
2016-12-20T23:21:27.020Z: writing policy to vault: namespace=dev unit=howdy-http policy=dev__howdy-http datacenter=texas
2016-12-20T23:21:27.734Z: instructing dc1's service scheduler to handle service container
2016-12-20T23:21:28.518Z: ======> workflow completed <======
```

The logs indicate exactly what Nelson executed, and any internal errors that happened during deployment will also appear in the log.

<h3 id="cli-stacks-runtime" class="linkable">
  Runtime
</h3>

Nelson can tell you about what it has logically done, and about its internal state. While this is most frequently useful, during debugging you might want to know the *actual* status of something in the runtime you asked Nelson to deploy your application too. In this case, the `runtime` command becomes useful, as it will fetch information from the scheduler and from consul as to the **current** state of your application.

```
λ ./bin/nelson stacks runtime f81c7a504bf6
==>> Stack Status
  STATUS:   ready
  EXPIRES:  2016-12-23T19:18:46Z

==>> Scheudler
  PENDING:    0
  RUNNING:    1
  COMPLETED:  0
  FAILED:     0

==>> Health Checks
  ID                                        NODE              STATUS   DETAILS
  c1443df51d7ed016f753357c56e917799ba66a7c  ip-10-113-130-88  passing  service: default howdy-http--1-0-348--qs1r9ost check
```

In the event you have multiple instances deployed (this example only has one), you will see all the relevant health checks listed here.

<h3 id="cli-stacks-redeploy" class="linkable">
  Redeployment
</h3>

There are occasions where you might want to try re-deploying a stack: for example, if your stack was idle for a long time and Nelson cleaned it up, as per the policy, or when the workflow had an error during deployment that was no fault of the user. These are both legit cases for re-deploying a stack, so the CLI supports it as a first-class stack operation. Redeployment will *cause a brand new deployment* of the same release version. The following is an example:

```
λ nelson stacks redeploy ec695f081fbd
==>> Redeployment requested for ec695f081fbd
```

<h2 id="cli-tips" data-subheading-of="cli">Tips</h2>

Generally speaking the command line client is just like any other CLI tool, and it integrates perfectly well with other bash tools. What follows below is just a useful laundry list of bash commands working in concert with the *Nelson* CLI.

<h3 id="cli-tips-sorting" class="linkable">
  Sorting Tables
</h3>

It is possible to sort the resulting tables from the command line client by using the built-in `sort` command of bash:

```
λ nelson units list --namespaces dev --datacenters massachusetts | sort -k4
  GUID          NAMESPACE  UNIT              VERSION
  c0e4281e9c9d  dev      howdy-http        0.38
  44b17835fe41  dev      howdy-batch       0.38
  a0d3e7843856  dev      heydiddlyho-http  0.33
```

The `-k4` tells the `sort` command to order the output by the forth column. This works with arbitrary indexes, so you can sort by any column you want.

<h3 id="cli-tips-filtering" class="linkable">
  Filtering Tables
</h3>

Sometimes you might want to just find a specific line in a larger table (perhaps to see if something was terminated, for example). Whilst `grep` will work just fine, it is often useful to retain the headers for each column so you know what you're looking at. This can easily be achieved with `awk`:

```
λ nelson units list -ns dev -d massachusetts | awk 'NR==1 || /batch/'
  GUID          NAMESPACE  UNIT              VERSION
  44b17835fe41  dev      howdy-batch       0.38
```


<h1 id="manifest" class="page-header">Manifest Settings</h1>

The manifest is the primary method to control parameters about your deployment: what it should be called, how big it should be, where it should be deployed too etc etc.

<h2 id="datacenters" data-subheading-of="manifest">Datacenters</h2>

Given that not all resources may be available in all datacenters, *Nelson* understands that you may at times want to be picky about which particular datacenters you deploy your *units* into. With this in mind, *Nelson* supplies the ability to whitelist and blacklist certain datacenters.

```
datacenters:
  only:
    - portland
    - redding
```

In this example, using `only` forms a whitelist. This would only deploy to our fictitious `portland` and `redding` datacenters.

```
datacenters:
  except:
    - seattle
```

Using the `except` keyword forms a blacklist, meaning that this deployment would deploy everywhere `except` in seattle. The common use case for this would be that `seattle` had not upgraded to a particular bit of platform infrastructure etc.

<h2 id="manifest-load-balancers" data-subheading-of="manifest">Load Balancers</h2>

Load balancers are another top-level manifest declaration. For an overview of the LB functionality, see the [usage guide](index.html#user-guide-lbs). LBs are first declared logically, with the following block:

```
loadbalancers:
  - name: foo-lb
    routes:
      - name: foo
        expose: default->8444/http
        destination: foobar->default
      - name: bar
        expose: monitoring->8441/https
        destination: foobar->monitoring
```

Next - just like units - you need to declare a [plan](#manifest-plans) in order to specify size and scale of the LB. Plans are discussed in-depth [later in this reference](#manifest-plans), but here's an example for completeness:

```
- name: lb-plan
  instances:
    desired: 4
```

Depending on the backend in use (e.g. AWS), various fields in the `plan` will be ignored, so be sure to check with your administrator which fields are required.

<h2 id="manifest-namespaces" data-subheading-of="manifest">Namespaces</h2>

Namespaces represent virtual "worlds" within the shared computing cluster. From a manifest specification perspective, there are a few interesting things that *Namespaces* enable.

<h3 id="manifest-namespace-sequencing" class="linkable">
  Committing
</h3>

During a release event each unit will only be deployed into the default namespace (this is usually `dev`). After the initial release the unit can be deployed into other namespaces by "committing" it. This can be done via the [commit endpoint of the API](#api-units-commit), or [`nelson unit commit` in the CLI](#cli-unit-commit).

In an ideal world, whatever system you use for testing or validation, the user would integrate with the Nelson API so that applications can be automatically committed from namespace to namespace.

<h2 id="manifest-plans" data-subheading-of="manifest">Plans</h2>

When deploying a unit in a given namespace, it is highly probable that the `unit` may require different resource capacity in each target namespace. For example, a unit in the `dev` namespace is unlikely to need the same resources that the same service in a high-scale `production` namespace would need. This is where plans come in, they define resource requirements, constraints, and environment variables.

<h3 id="manifest-plan-specification" class="linkable">
  Plan Specification
</h3>

Plans define resource requirements, constraints, and environment variables, and are applied to units in a given namespace. Take the following example:

```
plans:
  - name: dev-plan
    cpu: 0.25
    memory: 2048

  - name: prod-plan
    cpu: 1.0
    memory: 8192
    instances:
      desired: 10

namespaces:
  - name: dev
    units:
      - ref: foobar
        plans:
          - dev-plan

  - name: qa
    units:
      - ref: foobar
        plans:
          - prod-plan
```

This example defines two plans: `dev-plan` and `prod-plan`. The fact that the words `dev` and `prod` are in the name is inconsequential, they could have been `plan-a` and `plan-b`. Notice that under the namespace stanza each unit references a plan, this forms a contract between the unit, namespace, and plan. The example above can be expanded into the following deployments: the `foobar` unit deployed in the `dev` namespace using the `dev-plan`, and the `foobar` unit deployed in the `production` namespace using the `prod-plan`

As a quick note the `memory` field is expressed in megabytes, and the `cpu` field is expressed in number of cores (where 1.0 would equate to full usage of one CPU core, and 2.0 would require full usage of two cores). If no resources are specified Nelson will default to 0.5 CPU and 512 MB of memory.

<h3 id="manifest-resource-requests-limits" class="linkable">
  Resource Requests and Limits
</h3>

The resources specified by `cpu` and `memory` are generally treated as resource *limits*, or upper bounds on the amount of resources allocated per unit. Some schedulers, like Kubernetes, also support the notion of a resource *request*, or lower bound on the resources allocated. An example of this might be an application that requires some minimum amount of memory to avoid an out of memory error. For schedulers that support this feature, resource requests can be specified with a `cpu_request` and `memory_request` field in the same way `cpu` and `memory` are specified.

```
plans:
  - name: dev-plan
    memory_request: 256
    memory: 512
```

In the event that a request is specified, a corresponding limit must be specified as well. For schedulers that support resource requests, if only a limit if specified then the request is set equal to the limit. For schedulers that do not support resource requests, requests are ignored and only the limits are used.

<h3 id="manifest-matrix-specification" class="linkable">
  Deployment Matrix
</h3>

In order to provide experimenting with different deployment configurations Nelson allows a unit to reference multiple plans for a single namespace. Think of it as a deployment matrix with the form: units x plans. Use cases range from providing different environment variables (for S3 paths) to cpu / memory requirements. In the example below, the unit `foobar` will be deployed twice in the dev namespace, once with `dev-plan-a` and once with `dev-plan-b`.

```
plans:
  - name: dev-plan-a
    cpu: 0.5
    memory: 256
    environment:
       - S3_PATH=some/path/in/s3

  - name: dev-plan-b
    cpu: 1.0
    memory: 512
    environment:
       - S3_PATH=some/different/path/in/s3

namespaces:
  - name: dev
    units:
      - ref: foobar
        plans:
          - dev-plan-a
          - dev-plan-b
```

<h3 id="manifest-namespace-environment-variables" class="linkable">
  Environment Variables
</h3>

Given that every unit is deployed as a container, it is highly likely that configuration may need to be tweaked for deployment into each environment. For example, in the `dev` namespace, you might want a `DEBUG` logging level, but in the `production` namespace you might want only want `ERROR` level logging. To give a concrete example, lets consider the popular logging library [Logback](http://logback.qos.ch/), and a configuration snippet:

```
<?xml version="1.0" encoding="UTF-8"?>
<included>
  <logger name="your.program" level="${CUSTOM_LOG_LEVEL}" />
</included>
```

In order to customize this log level on a per-namespace basis, one only needs to specify the environment variables in the unit specification of the namespace. Here's an example:

```
plans:
  - name: dev-plan
    environment:
       - CUSTOM_LOG_LEVEL=INFO

namespaces:
  - name: dev
    units:
      - ref: foobar
        plans:
          - dev-plan
```

The `.nelson.yml` allows you to specify a dictionary of environment variables, so you can add as many as your application needs. **Never, ever add credentials using this mechanism**. Credentials or otherwise secret information should not be checked into your source repository in plain text. For information on credential handling, see [the credentials section of the user guide](index.html#user-guide-credentials).

<h3 id="manifest-plan-health-checks" class="linkable">
  Scaling
</h3>

The `plan` section of the manifest is where users can specify the scale of deployment for a given `unit`. Consider the following example:

```
plans:
  - name: default-foobar
    cpu: 0.5
    memory: 512
    instances:
      desired: 1
```

At the time of writing *Nelson* does not support auto-scaling. Instead, Nelson relies on "overprovisioned" applications and cost savings in a converged infrastructure where the scheduling sub-systems know how to handle over-subscription.

<h3 id="manifest-plan-health-checks" class="linkable">
  Health Checks
</h3>

Health checks are defined at the service/port level, and multiple health checks can be defined for a single service/port.
Health checks are used by Consul to determine the health of the service. All checks must be passing for a service to be
considered healthy by Consul. Here's an example of defining a health check in the manifest:

```
units:
  - name: foobar
    description: description for the foo service
    ports:
      - default->9000/http

plans:
   - name: dev-plan
     health_checks:
       - name: http-status
         port_reference: default
         protocol: http
         path: "/v1/status"
         timeout: "10 seconds"
         interval: "2 seconds"

namespaces:
  - name: dev
    units:
      - ref: foobar
    plans:
       - dev-plan
```

In this example, foobar service's `default` port will have an http health check that queries the path `/v1/status` every 2 seconds in the dev namespace. Below is an explanation of each field in the `health_checks` stanza:

<table class="table table-striped">
  <thead>
    <tr>
      <td><strong>Field</strong></td>
      <td><strong>Description</strong></td>
    </tr>
  </thead>
  <tbody>
    <tr>
      <td><code>name</code></td>
      <td>A alphanumeric string that identifies the health check, it can contain alphanumeric characters and hyphens.</td>
    </tr>
    <tr>
      <td><code>port_reference</code></td>
      <td>Specifies the port reference which the service is running. This must match up with the port label defined in the ports stanza of units.</td>
    </tr>
    <tr>
      <td><code>protocol</code></td>
      <td>Specifies the protocol for the health check. Valid options are http, https, and tcp.</td>
    </tr>
    <tr>
      <td><code>path</code></td>
      <td>Specifies the path of the HTTP endpoint which Consul will query. The IP and port of the service will be automatically resolved so this is just the relative URL to the health check endpoint. This is required if protocol is http or https.</td>
    </tr>
    <tr>
      <td><code>timeout</code></td>
      <td>Specifies how long Consul will wait for a health check. It is specified like: "10 seconds".</td>
    </tr>
    <tr>
      <td><code>interval</code></td>
      <td>Specifies the frequency that Consul will preform the health check. It is specified like: "10 seconds".</td>
    </tr>
  </tbody>
</table>

<h3 id="manifest-traffic-shift" class="linkable">
  Traffic Shifting
</h3>

Nelson has the ability to split traffic between two deployments when a new deployment is replacing and older one. A
traffic shift is defined in terms of a traffic shift policy and a duration. A traffic shift policy defines a function
which calculates the percentage of traffic the older and newer deployments should receive given the current moment in time.
As time progresses the weights change and Nelson updates the values for the older and newer deployments. It should be
noted that it does not make sense to define a traffic shifting policy for a unit that is run periodically, i.e. define a
`schedule`. Nelson will return a validation error if a traffic shifting policy is defined for such a unit.

At the time of writing there are two traffic shift policies: `linear` and `atomic`. The `linear` policy is a simple function that shifts
traffic from the older version to the newer version linearly over the duration of the traffic shift. The `atomic` policy
shifts traffic all at once to the newer version.

Traffic shifts are defined on a `plan` as it is desirable to use different policies for different namespaces. For
example in the `dev` namepsace one might want to define a `linear` policy with duration of 5 minutes, while in `prod`
the duration is 1 hour.

Below is an example of using traffic shifting:

```
units:
  - name: foobar
    description: description for the foo service
    ports:
      - default->9000/http

plans:
   - name: dev-plan
     traffic_shift:
       policy: atomic
       duration: "5 minutes"

   - name: prod-plan
     traffic_shift:
       policy: linear
       duration: "60 minutes"

namespaces:
  - name: dev
    units:
      - ref: foobar
    plans:
       - dev-plan

  - name: prod
    units:
      - ref: foobar
    plans:
       - prod-plan
```

Finally, Nelson provides a mechanism to reverse a traffic shift if it is observed that the newer version of the
deployment is undesirable. Once a traffic shift reverse is issued, Nelson will begin to shift traffic back to the
older deployed as defined by the traffic shifting policy. The reverse will shift traffic backwards until 100% is
being routed to the older deployment. Once this happens the newer deployment will no longer receive traffic and will be
eventually cleaned up by nelson. No other intervention is needed by the user after a reverse is issued.

<h2 id="manifest-notifications" data-subheading-of="manifest">
  Notifications
</h2>

Nelson can notify you about your deployment results via slack and/or email. Notifications are sent for a deployment when:

* a deployment has successfully completed or failed
* a deployment has been decommissioned

The following is a simple example that configure both email and slack notifications:


```
notifications:
  email:
    recipients:
      - greg@one.verizon.com
      - tim@one.verizon.com
  slack:
    channels:
      - infrastructure
      - devops
```

<h2 id="units" data-subheading-of="manifest">Units</h2>

A "unit" is a generic, atomic item of work that Nelson will attempt to push through one of its workflows (more on workflows later). Any given Unit represents something that can be deployed as a container, but that has distinct parameters and requirements.

<h3 id="manifest-unit-ports" class="linkable">
  Ports
</h3>

Units can be run either as a long-running process (service) or periodically (job). While services are meant to be long running processes, jobs are either something that needs to be run on a re-occurring schedule, or something that needs to be run once and forgotten about. Jobs are essentially the opposite of long-lived services; they are inherently short-lived, batch-style workloads.

Units that are meant to be long running typically expose a TCP port. A minimal example of a unit that exposes a port would be:

```
- name: foobar-service
  description: description of foobar
  ports:
    - default->8080/http
```

This represents a single service, that exposes HTTP on port `8080`. When declaring ports, the primary application port that is used for routing application traffic must be referred to as `default`. You can happily expose multiple ports, but only the `default` port will be used for automatic routing within the wider system. Any subsystem that wishes to use a non-default port can happily do so, but must handle any associated concerns. For example, one might expose a `monitoring` port that a specialized scheduler could call to collect metrics.

For completeness, here's a more complete example of a service unit that can be used in your `.nelson.yml`:

```
- name: foobar
  description: description of foobar
  workflow: magnetar
  ports:
    - default->8080/http
    - other->7390/tcp
  dependencies:
    - ref: inventory@1.4
    - ref: db-example@1.0
```

The `ports` dictionary items must follow a very specific structure - this is how nelson expresses relationships in ports. Let's break down the structure:

<div class="clearing">
  <img src="images/port-syntax.png" />
  <small><em>Figure 2.3.1: port definition syntax</em></small>
</div>

It's fairly straight forward syntactically, but lets clarify some of the semantics:

<table class="table table-striped">
  <thead>
    <tr>
      <td><strong>Section</strong></td>
      <td><strong>Description</strong></td>
    </tr>
  </thead>
  <tbody>
    <tr>
      <td><code>name</code></td>
      <td>Provides a canonical handle to a given port reference. For example, if your service exposes multiple ports then you may want to give the primary service port the name <code>default</code> whilst also exposing another port called <code>monitoring</code>.</td>
    </tr>
    <tr>
      <td><code>port&nbsp;number</code></td>
      <td>Actual port number that the container has <a href="https://docs.docker.com/engine/reference/builder/#/expose">EXPOSE</a>'d at build time.</td>
    </tr>
    <tr>
      <td><code>protocol</code></td>
      <td>Wire protocol this port is using. Currently supported values are <code>http</code>, <code>https</code> or <code>tcp</code>. This information is largely informational, but may later be used for making routing decisions.</td>
    </tr>
  </tbody>
</table>

<h3 id="manifest-unit-dependencies" class="linkable">
  Dependencies
</h3>

For most units to be useful, it is very common to require another sub-system to successfully operate, and *Nelson* encodes this concept as `dependencies`. Dependencies need to be declared in the unit definition as a logical requirement:

```
- name: foobar-service
  description: description of foobar
  ports:
    - default->8080/http
  dependencies:
    - ref: inventory@1.4
```

The unit name of the dependency can typically be discovered using the *Nelson* CLI command `nelson units list`, which will display the logical dependencies available in the specified datacenter. If you declare a dependency on a system, it **must** exist in every namespace you are attempting to deploy into; in the event this is not the case, *Nelson* will fail to validate your manifest definition.

Having multiple dependencies is common: typically a service or job might depend on a datastore, message queue or another service. Once declared in *Nelson* as a dependency, the appropriate Lighthouse data will also be pushed to the datacenter, which enables the user to dynamically resolve their dependencies within whatever datacenter the application was deployed into.

<h3 id="manifest-unit-resources" class="linkable">
  Resources
</h3>

In addition to depending on internal elements like databases and message queues, any given units can also depend on external services (such as Amazon S3, Google search etc) and are declared under the resources stanza. What makes `resources` different to `dependencies` is that they are explicitly global to the caller. Regardless of where you visit [google.com](https://www.google.com) from, you always access the same service from the callers perspective - the fact that [google.com](https://www.google.com) is globally distributed and co-located in many edge datacenters is entirely opaque.

<div class="alert alert-warning" role="alert">
  It is critical that as a user, you only leverage the <code>resources</code> block for external services. If the feature is abused for internal runtime systems, multi-region and data-locality will simply not work, and system QoS cannot be ensured.
</div>

A resource consists of a name and an optional description. While it is always useful to declare an external dependency, it is required if credential
provisioning is needed (i.e. S3).


```
- name: foo
  description: description of foobar
  ports:
    - default->8080/http
  resources:
    - name: s3
      description: image storage
```

All resources must define a uri associated with it. Because a uri can be different between namespaces it is defined under the plans stanze. A use case for this is using a different bucket between qa and dev.

```
plans:
  - name: dev
    resources:
      - ref: s3
        uri: s3://dev-bucket.organization.com

  - name: qa
     resources:
       - ref: s3
         uri: s3://qa-bucket.orgnization.com

```

<h3 id="manifest-unit-schedules" class="linkable">
  Schedules
</h3>

Units that are meant to be run periodically define a `schedule` under the plans stanza. They can also optionally declare `ports` and `dependencies`. The example below will be run daily in dev and hourly in prod.

```
units:
  - name: foobar-batch
    description: description of foobar
    dependencies:
      - ref: inventory@1.4

plans:
  - name: dev
    schedule: daily

  - mame: prod
    schedule: hourly

namespaces:
  - name: dev
    units:
      - ref: foobar-batch
    plans:
       - dev-plan

  - name: dev
    units:
      - ref: foobar-batch
    plans:
       - prod-plan
```

Possible values for `schedule` are: `monthly`, `daily`, `hourly`, `quarter-hourly`, and `once`. These values can be mapped to the following cron expressions:

```
 monthly        -> 0 0 1 * *
 daily          -> 0 0 * * *
 hourly         -> 0 * * * *
 quarter-hourly -> */15 * * * *
```

The value `once` is a special case and indicates that the job will only be run once. If more control over a job's schedule is needed the `schedule` field can be defined directly with any valid cron expression.

```
plans:
  - mame: dev
    schedule: "*/30 * * * *"
```

The more powerful cron expression is typically useful when you require a job to run at a specific point in the day, perhaps to match a business schedule or similar.

<h3 id="manifest-unit-workflows" class="linkable">
  Workflows
</h3>

Workflows are a core concept in *Nelson*: they represent the sequence of actions that should be conducted for a single deployment `unit`. Whilst users cannot define their own workflows in an arbitrary fashion, each and every `unit` has the chance to reference an alternative workflow by its name. However, at the time of this writing there is only a single workflow (referenced as `magnetar`) for all `units`. The `magnetar` workflow first replicates the the required container to the target datacenter, and then attempts to launch the `unit` using the datacenters pre-configured scheduler. Broadly speaking this should be sufficient for the majority of deployable units.

If users require a specialized workflow, please contact the *Nelson* team to discuss your requirements.

<h3 id="manifest-unit-expiration-policies" class="linkable">
  Expiration Policies
</h3>

Nelson manages the entire deployment lifecycle including cleanup. Deployment cleanup is triggered via the deployments expiration date which is managed by the `expiration_policy` field in the plans stanza. The `expiration_policy` defines rules about when a deployment can be decommissioned. Below is a list of available expiration policies:

<table class="table table-striped">
  <thead>
    <tr>
      <td><strong>Reference</strong></td>
      <td><strong>Description</strong></td>
    </tr>
  </thead>
  <tbody>
    <tr>
      <td><code>retain-active</code></td>
      <td>Retain active is the default expiration policy for services. It retains all versions with an active incoming dependency</td>
    </tr>
    <tr>
      <td><code>retain-latest</code></td>
      <td>Retain latest is the default expiration policy for jobs. It retains the latest version</td>
    </tr>
    <tr>
      <td><code>retain-latest-two-major</code></td>
      <td>Retain latest two major is an alternative policy for jobs. It retains latest two major versions, i.e. 2.X.X and 1.X.X</td>
    </tr>
    <tr>
      <td><code>retain-latest-two-feature</code></td>
      <td>Retain latest two feature is an alternative policy for jobs. It retains the latest two feature versions, i.e. 2.3.X and 2.2.X</td>
    </tr>
  </tbody>
</table>

Nelson does not support manual cleanup, by design. All deployed stacks exist on borrowed time, and will (eventually) expire. If you do not explicitly choose an expiration policy (this is common), then Nelson will apply some sane defaults, as described in the preceding table.

<h3 id="manifest-unit-meta" class="linkable">
  Meta Tags
</h3>

Sometimes you might want to "tag" a unit with a given set of meta data so that later you can apply some organization-specific auditing or fiscal tracking program. In order to do this, Nelson allows you to tag - or label - your units. Here's an example:

```
units:
- name: example
  description: example
  ports:
  - default->9000/http
  meta:
  - foobar
  - buzzfiz
```

Meta tags may not be longer than 14 characters in length and can only use characters that are acceptable in a DNS name.

<h3 id="manifest-unit-alerting" class="linkable">
  Alerting
</h3>

Alerting is defined per unit, and then deployed for each stack that is created for that unit. Nelson is not tied to a particular alerting system, and as with the integrations with the cluster managers, Nelson can support integration with multiple monitoring and alerting backends. At the time of writing, Nelson only supports [Prometheus](https://prometheus.io/) as a first-class citizen. This is a popular monitoring and alerting tool, with good throughput performance that should scale for the majority of use cases.

From a design perspective, Nelson is decoupled from the actual runtime, and is not in the "hot path" for alerting. Instead, Nelson acts as a facilitator and will write out the specified alerting rules to keys in the Consul for the datacenter in question. In turn, it is expected that the operators have setup [consul-template](https://github.com/hashicorp/consul-template) (or similar) to respond to the update of the relevant keys, and write out the appropriate configuration file. In this way, Nelson delegates the actual communication / implementation of how the rules are ingested to datacenter operations.

The following conventions are observed when dealing with alerts:

1. Alerts are always defined for a particular unit.
1. All alerts are applied to the unit in any namespace, except those specified by opt-outs in the plan.
1. Notification rules must reference pre-existing notification groups, which are typically configured by your systems administrator, based on organizational requirements.

Let's consider an example, that expands on the earlier explanations of the unit syntax:

```
---
units:
  - name: foobar
    description: >
      description of foobar
    ports:
      - default->8080/http
    alerting:
      prometheus:
        alerts:
        - alert: instance_down
          expression: >-
            IF up == 0
            FOR 5m
            LABELS { severity = "page" }
            ANNOTATIONS {
              summary = "Instance {{ $labels.instance }} down",
              description = "{{ $labels.instance }} of job {{ $labels.job }} down for 5 minutes.",
            }
        rules:
          - rule: "job_service:rpc_durations_microseconds_count:avg_rate5m"
            expression: "avg(rate(rpc_durations_microseconds_count[5m])) by (job, service)"
```

The observant reader will notice the `alerting.prometheus` dictionary that has been added. When using the support for Prometheus, Nelson allows you to specify the native Prometheus alert definition syntax inline with the rest of your manifest. You can use any valid Prometheus alert syntax, and the alert definitions will be automatically validated using the Prometheus binary before being accepted by Nelson.

Whilst the alerting support directly exposes an underlying integration to the user-facing manifest API, we made the choice to expose the complete power of the underlying alerting system, simply because the use cases for monitoring are extremely varied, and having Nelson attempt to "translate" arbitrary syntax for a third-party monitoring system seem tedious and low value. We're happy with this trade off overall as organization change their monitoring infrastructure infrequently, so whilst its a "moving target" over time, it is slow moving.

<h4 id="manifest-unit-alerting-syntax">
  Alert Syntax
</h4>

Upon first glace, the alert block in the manifest can seem confusing. Thankfully, there are only three sections a user needs to care about. The table below outlines the alert definitions.

<table class="table">
  <thead>
    <tr>
      <td><strong>Dictionary</strong></td>
      <td><strong>Description</strong></td>
    </tr>
  </thead>
  <tbody>
    <tr>
      <td><code>prometheus.alerts</code></td>
      <td>An array of alert rules for your unit.  An alert rule is split into two keys: the <code>alert</code> and the <code>expression</code>.</td>
    </tr>
    <tr>
      <td><code>prometheus.alerts[...].alert</code></td>
      <td>The name of the alert. This alert name is used as a key for <a href="#alert-opt-outs">opt-outs</a> and appears throughout the alerting system. Select a name that will make sense to you when you get paged at 3am. The alert name should be in snake case, and must be unique within the unit. Valid characters are <code>[A-Za-z0-9_]</code>.</td>
    </tr>
    <tr>
      <td><code>prometheus.alerts[...].expression</code></td>
      <td>Prometheus expression that defines the alert rule. An alert expression always begins with `IF`. Please see the <a href="https://prometheus.io/docs/alerting/rules/">Prometheus documentation</a> for a full discussion of the expression syntax. We impose the further constraint that all alert expressions come with at least one annotation.</code>.</td>
    </tr>
  </tbody>
</table>

<h4 id="manifest-unit-alerting-rules-syntax">
  Rule Syntax
</h4>

In addition to specification of alerts, the manifest also allows for the specification of Prometheus rules. See the <a href="https://prometheus.io/docs/querying/rules/#recording-rules">Prometheus documentation on recording rules</a> for a discussion on the differences between alerts and recording rules.

<table class="table">
  <thead>
    <tr>
      <td><strong>Dictionary</strong></td>
      <td><strong>Description</strong></td>
    </tr>
  </thead>
  <tbody>
  <tr>
    <td><code>prometheus.rules</code></td>
    <td>An array of recording rules for your unit. <a href="https://prometheus.io/docs/querying/rules/#recording-rules">Recording rules</a> pre-calculate more complicated, expensive expressions for use in your alerting rules.  Like alert rules, each recording rule is split into two keys: <code>rule</code> and <code>expression</code>.</td>
  </tr>
  <tr>
    <td><code>prometheus.rules[...].rule</code></td>
    <td>Name of the recording rule.  It should be in snake case and unique within the unit.  Valid characters are <code>[A-Za-z0-9_]</code>.</td>
  </tr>
  <tr>
    <td><code>prometheus.rules[...].expression</code></td>
    <td>Prometheus expression that defines the recording rule. See the documentation for the <a href="https://prometheus.io/docs/querying/basics/">Prometheus query language</a> to get an overview of what sort of expressions are possible.</td>
  </tr>
</tbody>
</table>


<h4 id="manifest-unit-alerting-opt-out">
  Opting Out
</h4>

A good alert is an actionable alert.  In some cases, an alert may not be actionable in a given namespace.  A development team might know that quirks in test data in the qa namespace result in high request latencies, and any alerts on such are not informative.  In this case, an alert may be opted out via the `alert_opt_outs` array for the unit.

```
units:
  - name: foobar
    description: >
      description of foobar
    ports:
      - default->8080/http
    dependencies:
      - ref: cassandra@1.0
    alerting:
      prometheus:
        alerts:
          - alert: api_high_request_latency
            expression: >-
              IF ...

plans:
  - name: qa-plan
    cpu: 0.13
    memory: 2048
    alert_opt_outs:
       - api_high_request_latency

  - name: prod-plan
    cpu: 0.65
    memory: 2048

namespaces:
  - name: qa
    units:
      - ref: foobar
        plans:
          - qa-plan

  - name: production
    units:
      - ref: foobar
        plans:
          - prod-plan
```

In the above example, the `api_high_request_latency` alert for the `foobar` unit is opted out in the `qa` namespace.  The alert will still fire in `dev` and `prod` namespaces.  A key in the `alert_opt_outs` array must refer to an `alert` key under `alerting.prometheus.alerts` in the corresponding unit, otherwise Nelson will raise a validation error when trying to parse the supplied manifest.
