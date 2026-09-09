# HTTP admission and completion

This contract is covered by the servlet lifecycle and embedded-container tests.
HTTP admission protects server work, not the identity of an external caller.

## Admission boundaries

Choose the MVC interceptor or raw servlet filter, not both. Automatic
configuration rejects simultaneous activation to avoid charging the same
HTTP operation at two alternative boundaries. Method admission remains a
separate, explicitly configured boundary.

| Dispatch | Admission |
| --- | --- |
| Initial request | Resolve and check its policy. |
| Async continuation of the same admitted target | Keep the original outcome and start time; do not consume again. |
| Error, forward, include, or async dispatch to another target | Resolve and check the target's own policy. |

The target comprises HTTP method, URI, and query string. For MVC, a
continuation must also match the original handler, whether async processing
is managed by Spring or directly through the Servlet API.
A first-observed async dispatch without a
matching admission still gets checked. A prior fail-open or bypassed policy
also remains the same outcome on continuation; it is not a new admission.

Internal error pages are not automatically exempt. The error target can be
granted, rejected, bypassed by an explicit policy resolver, or fail according
to its failure mode. None of those outcomes refunds an earlier grant.
Nested dispatches must not overwrite the enclosing dispatch's measurement.
For an included fragment, the container may prevent changing the enclosing
HTTP status; a rejection still stops the protected fragment from executing.

The raw filter is registered for all servlet dispatcher types and supports
async processing. It uses the current dispatch URI (the include target for
an include), not a stale MVC route attribute left by the calling handler.
Applications manually registering adapters own their mappings and must not
install duplicate instances or overlapping alternative adapters.

## Measurements

Only granted policies with reporting enabled and guards emit automatic
reports. Synchronous measurement ends when processing returns or throws.
Async measurement ends at completion, not when the initiating thread exits.
Timeout/error notifications do not necessarily finish an async operation;
completion does. A listener follows restarted async cycles and reports at
most once, including when MVC completion and container completion both occur.
If a custom/container lifecycle refuses listener registration, preserve the
admission outcome but skip the uncertain measurement rather than inventing an
early sample or charging again on continuation.

The measured interval starts after admission and includes async waiting and
response processing. Nested operations have separate, potentially overlapping
intervals. This remains whole-operation timing, not per-dependency timing or
proof that the HTTP client received the response. Streaming applications
should supply independent measurements when whole-stream duration is not a
meaningful sample. See [usage](usage.md#latency-measurement).

## Verification and boundaries

Regression tests cover MVC async redispatch, raw servlet completion, error
and forward target admission, nested dispatch state, and configuration
overlap. Tests use local fixtures and no live credentials or server.
Real embedded Tomcat tests additionally exercise async restart, MVC completion,
forward/include mapping, and rejection of a separately protected error target.

The initial 20 regression cases had 15 assertion failures against main
`4b9a894`, with no test errors. Follow-up cases cover refused listener
registration and changed query strings, alongside the real-container suite.
Combined with the activation-safety changes, the local reactor passes 93 tests,
including 24 lifecycle/registration cases and ten embedded-container cases
across both alternative HTTP adapters.
Exact-head CI remains a gate; the umbrella audit is not complete.

The lifecycle follows Spring's [async MVC processing](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-async.html)
and the Servlet [async listener contract](https://jakarta.ee/specifications/platform/11/apidocs/jakarta/servlet/asynclistener).
This does not add WebFlux support, move method interception, change HA policy,
or guarantee ordering after every application authorization/business guard.
Those placement choices still belong to the protected application's design.
