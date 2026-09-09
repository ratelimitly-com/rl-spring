# Usage and limitations

This guide describes the current Spring integration, not a future API. The
[README](../README.md) introduces the two operations and gives small examples.

## Choose the work you want to protect

HTTP admission and method admission protect different operations. Choose the
boundary that represents the work you intend to account for; enabling both
does not combine them into one atomic check.

- **Methods:** use `@RateLimited` on Spring-managed beans and disable servlet
  enforcement if you do not also want HTTP admission checks.
- **MVC requests:** use the MVC interceptor and disable method enforcement
  when route admission alone is sufficient.
- **Your own integration:** inject the shared `RateLimitlyClient` and disable
  both automatic adapters. You decide when to request resources and report
  measurements.

See [configuration](configuration.md#select-an-integration) for each mode.
The raw servlet filter is an alternative to the MVC interceptor, not an
additional layer to enable casually. It runs before MVC has resolved a route
template. Automatic HTTP and method policies use different bucket prefixes by
default; a method rejection does not undo an earlier HTTP grant.

The HTTP adapters preserve admission across async continuation of the same
operation. Error pages, forwards, includes, and different async targets are
not blanket-exempt: they have their own policy checks. The
[HTTP lifecycle guide](servlet-lifecycle.md) defines these boundaries and the
completion measurements.

RateLimitly accounts for admitted work, not successful business results. A
later application exception, another guard, or an HTTP error does not refund
resources already consumed by a grant. Configure the boundary with that
accounting contract in mind.

## Outcomes

The default adapters distinguish these outcomes:

| Outcome | MVC interceptor / servlet filter | Annotated method |
| --- | --- | --- |
| Grant | Continue processing. | Execute the method. |
| Rejection | Send HTTP 429 if the response is not already committed. | Throw `RateLimitDeniedException`. |
| Client request failure, fail-open | Continue without a usable decision. | Execute without a usable decision. |
| Client request failure, fail-closed | Send HTTP 503 if the response is not already committed. | Throw `RateLimitUnavailableException`. |

For declared `CompletionStage` or `CompletableFuture` return types, method
rejection or fail-closed failure is represented by an exceptionally completed
future. A custom subtype that cannot accept a `CompletableFuture` receives the
admission exception synchronously instead of an incompatible return value.
The admission check itself is still synchronous. Custom denial and failure handlers can
change these mappings. Method exceptions are not automatically mapped to HTTP
429/503: an MVC application exposing them must supply its own exception mapping.

`ratelimitly.default-fail-mode` is `open` by default. The README explicitly
selects `closed`; choose deliberately for the protected resource. These modes
handle client `RateLimitlyException` failures during admission, not arbitrary
exceptions from property binding, policy resolution, or application code.

Local metrics and automatic reports are best effort and cannot replace the
application's result or exception with an ordinary reporting/observation
failure. See the [outcome and method-boundary contract](admission-outcomes.md)
for async identity/cancellation, nested proxy calls, and advice ordering.

A missing response does not establish whether resources were consumed. Do
not interpret request failure as a confirmed rejection or blindly repeat the
business operation. The Java client's own bounded retry policy belongs to the
same logical request; see its [request-policy guide](https://github.com/ratelimitly-com/rl-java-client/blob/main/docs/request-policy.md).

## Resource names and definitions

Resources and latency trackers are content-defined: application code supplies
names and definitions, and the Java client derives their canonical IDs. No
separate resource-registration call is needed.

- A bucket definition includes the exact name, rate window, and rate limit.
- A tracker definition includes the exact name, TTL, maximum samples, and
  minimum-sample threshold. Its reporting and guarding applications must use
  matching definitions. The guard threshold and reported measurement are not
  part of the tracker identity.

Definitions and requests must fit the API-key quotas. Names should be stable
and intentional; unbounded values from URLs or method arguments can create
large numbers of distinct buckets, trackers, or labels. See the Java client's
[canonical ID documentation](https://github.com/ratelimitly-com/rl-java-client/blob/main/docs/api.md).

The default Spring resolvers form names as follows:

| Integration | Default bucket name |
| --- | --- |
| Method with `context = "checkout"` | `method:checkout` |
| Method without an explicit context | `method:<fully-qualified-class>#<method-name>` |
| MVC, where the route template is available | `http:<HTTP-method>:<route-template>` |
| Servlet fallback without a template or handler | `http:<HTTP-method>:<request-URI>` |

Prefixes are configurable. The current default method identity does not
include parameter types, so overloaded methods share a name unless you give
them distinct contexts. The MVC resolver can also fall back to the handler's
class/method name. The raw filter usually sees the URI rather than a template.

## Method annotations and expressions

`@RateLimited` may be placed on a class or method. A method annotation takes
precedence as a whole; it is not merged field-by-field with a class annotation.
Use public, non-final methods on non-final Spring-managed classes and call
through their Spring proxy. Self-invocation bypasses this protection.

| Attribute | Meaning when specified |
| --- | --- |
| `context` | Bucket suffix, after the configured method prefix. |
| `rate` | Positive rate limit; otherwise the method default is used. |
| `window` | Window such as `1s`, `500ms`, or `PT1M`; blank uses the default. |
| `tokensRequested` | Positive token quantity; otherwise the method default is used. Zero does not make a guard-only annotation. |
| `policy` | Local policy name available to custom resolvers and handlers; not a separate field sent in the resource request or printed by built-in debug logs. |
| `label` | Optional metrics label, sent only when label emission is enabled. |
| `guards` | Latency guards included in the same resource request. |
| `reportLatency` | Permit automatic method-duration reporting; both this and the global method reporting flag must be true. |

`@MethodLatencyGuard` defines `service` (the tracker name), `thresholdMs`
(default 250), `ttlMs` (300000), `maxSamples` (120), and
`minSampleThreshold` (1). There is no buffer-size setting. Defaults are not a
promise that a particular API key permits the resulting tracker definition.

`context`, `policy`, `label`, and guard `service` values accept `{...}` SpEL
segments. Available inputs include named method arguments, positional `#a0` /
`#p0`, `#arguments`, and, during an HTTP invocation, `#request`,
`#pathVariables`, and `#queryParams`. A whole value beginning with `#` is also
evaluated as an expression.

`#apiKeyId` is reserved for the configured API key's unsigned decimal ID. It
is not the secret and cannot be replaced by method arguments or HTTP values.
It is empty when an application provides its own client without configuring
an API key. Read colliding inputs through their explicit maps or positions.
The metadata names `arguments`, `method`, `target`, `request`, `pathVariables`,
`queryParams`, and positional aliases `aN`/`pN` are also reserved. Other names
resolve in this order: named method argument, path variable, query parameter.
A null argument still takes precedence. Explicit map/argument references are
usually clearer. Without HTTP context, `request` is null and both maps are empty.

A guard service expression resolving to null or blank raises a policy error
before admission; it does not silently remove the guard, even in fail-open mode.
Substituted values are data and are not evaluated again as expressions.

Expressions must come from trusted application configuration or annotations,
not from user input. See [security guidance](../SECURITY.md) and the
[boundary and ownership contract](security-and-ownership.md).

## Programmatic policies

The injected Java client supports resource-only, guard-only, combined, and
empty resource requests. An empty request returns local success without
network activity. It also supports independent latency reports.

The built-in annotation and servlet resolvers always create one resource.
For guard-only or multi-resource adapter checks, supply a
`RateLimitlyPolicyResolver<MethodInvocationContext>` or
`RateLimitlyPolicyResolver<ServletRequestContext>` bean. A resolver returns a
`RateLimitlyPolicy` containing resources, guards, reporting and failure flags,
or returns `null` to bypass that adapter's admission check. Method interception
still requires an applicable `@RateLimited` annotation.

Other extension points are `RateLimitlyLabelResolver`, method/HTTP denial
handlers, and `RateLimitlyFailureHandler`. Providing a client bean replaces
automatic client construction. Treat that bean's lifetime and executor
ownership explicitly; see the Java client's
[lifecycle guide](https://github.com/ratelimitly-com/rl-java-client/blob/main/docs/lifecycle.md).

## Latency measurement

Independent measurements should describe the actual service being tracked.
Call the injected client's `reportLatency` or `reportLatencyAsync` operation;
these calls do not need a preceding resource request or an automatic reporting
flag. Delivery semantics and asynchronous ownership are documented in the
[Java API guide](https://github.com/ratelimitly-com/rl-java-client/blob/main/docs/api.md).

Current automatic reporting is narrower:

- After an admitted synchronous method completes or throws, it reports the
  whole method duration to each configured guard's tracker, if enabled.
- For a returned `CompletionStage`, measurement ends in `whenComplete`,
  including exceptional completion and cancellation. The original application
  stage is returned, preserving its identity and cancellation behavior;
  completion does not wait for reporting to finish. Admission still blocks
  the invoking thread.
- The servlet adapter reports the measured processing interval to the
  policy's guards, if present. Async waiting is included, with a single report
  at completion rather than at the initiating thread's exit. Nested targets
  retain separate measurements. The default servlet policy has no guards and
  therefore emits no automatic latency report.
- Rejections and client-failure fail-open paths do not automatically report
  latency. With multiple guards, each receives the same whole-operation
  measurement; this is not per-dependency instrumentation.
- Automatic measurements are whole milliseconds, rounded down with a minimum
  of 1 ms. The reporting call is synchronous even in the completion callback.
  Caught client reporting failures do not change the admission decision.

Use automatic reporting only when that interval represents the service named
by the tracker. Disable it when you provide independently measured samples,
as the guarded README example does.

## Known limitations

- WebFlux and Reactor enforcement are not implemented. Unsupported
  `webflux.*` properties are rejected when RateLimitly is enabled.
- Proxy self-invocation is not intercepted. Ordering with application security,
  transaction, retry, and other advice must be configured and verified by the
  application; equally ordered advice has no guaranteed relative order.
- The middleware does not automatically refund an earlier grant after later
  rejection or application failure.

These are current limitations, not completed security guarantees. Their
review and remediation are tracked in the [public-readiness plan](public-readiness-plan.md).
