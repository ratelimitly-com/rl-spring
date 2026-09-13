# Sample application

The [sample module](../ratelimitly-spring-boot-sample-app) demonstrates MVC and
method admission in a local Spring Boot application. It is not a production
configuration or a package intended for Maven publication.

First complete the [local build](../CONTRIBUTING.md#build-and-test). From the
repository root, start the sample:

```sh
mvn -f ratelimitly-spring-boot-sample-app/pom.xml spring-boot:run
```

It listens on port 8080. RateLimitly is disabled by default, so no credential
is needed to inspect the HTTP routes. The first two routes below are useful
for a basic local check:

```sh
curl -sS http://localhost:8080/api/demo/health
curl -sS http://localhost:8080/api/demo/users/alice
```

Other sample routes are `/api/demo/customers/{customerId}` with optional
`region` / `status` parameters, and `/api/demo/slow/{customerId}` with optional
`region`. The slow method simulates about 300 ms of work; its latency guard
threshold is 250 ms.

## Opt in to RateLimitly traffic

Only when you intend to send requests, supply `RATELIMITLY_API_KEY` through
your environment or secret manager and set `RATELIMITLY_ENABLED=true` before
starting the sample. Do not place the key in the command or the YAML file.
An API key with quotas suitable for the sample's bucket/tracker definitions
and reachable discovery/service endpoints is required for this optional demo.

The sample enables both MVC and method policies. A request to an annotated
service can therefore make two independent resource requests. Defaults here
differ from the library: HTTP and method defaults use a 10-token/1-second
window and sample-specific bucket prefixes. Method annotations can override
method defaults; they do not change the separate HTTP admission policy.
The sample retains the library's fail-open default unless you explicitly
configure `ratelimitly.default-fail-mode=closed`.

After enabling traffic, a successful HTTP response alone does not prove a
grant: fail-open also allows work when the client cannot obtain a decision.
Interpret responses with the configured failure mode and diagnostics. A
particular sequence of grants or rejections is not guaranteed; it depends on
the resource/tracker state and delivery outcomes.

## Optional manual helpers

Run helpers only against an application and RateLimitly environment you intend
to exercise. They are not part of the credential-free contributor test gate.

```sh
SCENARIO=both bash scripts/test-rate-limit.sh
bash scripts/demo-guard.sh
```

`test-rate-limit.sh` sends bursts to sample routes and expects at least one
HTTP 429 per selected scenario. It fails that expectation with enforcement
disabled and is not a deterministic server-correctness test. Its configurable
unexpected-status allowance is controlled by `MAX_OTHER_RESPONSES`.

`demo-guard.sh` shows responses and timing for the slow route. It makes a
separate request for timing, so the displayed status/payload and timing need
not describe the same admission outcome. Prefer a single `curl` request when
you need a matched status and duration:

```sh
curl -sS -w '\nstatus=%{http_code} duration=%{time_total}s\n' \
  'http://localhost:8080/api/demo/slow/cust-123?region=us-east'
```

Enable Spring DEBUG diagnostics only after reviewing their data exposure:
set `ratelimitly.client.debug=true` and the logger
`logging.level.com.ratelimitly.spring=DEBUG`. See [security guidance](../SECURITY.md).
