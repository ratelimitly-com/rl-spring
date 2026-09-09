# Security boundaries and resource ownership

This document records the verified boundary and ownership contract of the
public Spring integration. See the [usage guide](usage.md) for application setup.

## Expressions and identifiers

Expression source is trusted application code/configuration, not request data.
SpEL supports method and type access; this integration is not an expression
sandbox. Substituted argument/path/query values are data and are not parsed
again as expressions. See Spring's [expression security guidance](https://docs.spring.io/spring-framework/reference/core/expressions/evaluation.html#expressions-evaluation-context).

Reserved names are `apiKeyId`, `arguments`, `method`, `target`, `request`,
`pathVariables`, `queryParams`, and positional aliases `aN`/`pN`. They must not
be replaced by similarly named method arguments or HTTP inputs. For other
names, method arguments take precedence over path variables, which take
precedence over query parameters. Resolution must not depend on which variable
an expression reads first. Use the explicit maps or `arguments[index]` to
read colliding input names. Without a request, `request` is null and both
HTTP maps are empty.

A configured latency guard whose service expression resolves to null/blank
is a policy error, not a request to remove protection. Fail before admission;
fail-open applies to client request failures, not invalid policy resolution.

Names remain application-chosen. Route templates group MVC requests, while
the raw filter usually uses the actual URI; dynamic paths/arguments can create
unbounded bucket or label cardinality. No hashing scheme prevents that. Use
stable policy names, explicitly bounded dimensions, and deliberate resolver
overrides. No implicit truncation or new naming scheme is introduced here.

## Errors and diagnostics

Default HTTP unavailability responses expose only a generic 503 message, not
the client exception text. Already-committed responses cannot be replaced;
the server-side exception retains its cause for application handling.

Built-in adapter debug logs report operation counts, outcome kinds, and numeric
timing/server metadata, not URIs, argument-derived resource names, policy names,
labels, credentials, or exception messages. This does not sanitize application
logs, client diagnostics, configuration dumps, framework error pages, or custom
handlers. Do not expose stack traces or configuration/diagnostic endpoints.

## Lifetime and dependency contract

Spring closes the automatically created client at context shutdown, including
cleanup after a later bean fails startup. The Java client owns its default
executor and transport; this middleware creates no executor of its own.
Admission and automatic reporting use synchronous client calls. Pending business
futures remain application-owned and are not drained/cancelled by the middleware.

A supplied client bean replaces automatic construction. Spring normally infers
its `close` destroy method; use `@Bean(destroyMethod = "")` only when another
owner is responsible for closing it. Applications sharing a client across
contexts must choose one owner. An executor supplied via a custom client or
configuration mapper remains application-owned under the Java client's
[lifecycle contract](https://github.com/ratelimitly-com/rl-java-client/blob/main/docs/lifecycle.md).

The current starter declares Spring MVC, Servlet API, and Micrometer core as
transitive dependencies. They are not advertised as independently optional
modules. A non-web application need not run a servlet container, and a
MeterRegistry bean is optional; disabling metrics does not remove the Micrometer
dependency. Arbitrary exclusions of required transitive dependencies are not
a supported packaging mode. Client-only mode can disable both adapters.

## Measurements and remaining preparation

The [outcome](admission-outcomes.md) and [HTTP lifecycle](servlet-lifecycle.md)
tests cover normal/exceptional completion, async continuation/cancellation,
denial, fail-open, and reporting failures. Whole-operation reporting remains
coupled to configured guards and is not per-dependency timing. A cancelled
future does not prove underlying work stopped; a sent report is not an
acknowledgement. Independent reports through the client remain available.
These limitations are tracked in [issue #2](https://github.com/ratelimitly-com/rl-spring/issues/2)
and retained for the initial source release. This closeout does not redesign measurement
policy or claim all application configurations are safe.

Public launch verification is tracked in
[issue #1](https://github.com/ratelimitly-com/rl-spring/issues/1).
The standalone [consumer fixture](../integration-tests/consumer/pom.xml)
validates installed artifacts, not reactor test classes or private server code;
see [contributor commands](../CONTRIBUTING.md#build-and-test).

## Regression evidence

The initial 14 boundary tests produced 9 assertion failures and 2 expression
errors before the fixes (3 passed). They reproduced unsafe diagnostic output,
metadata shadowing, read-order-dependent lookup, blank guard omission, and
null-argument rejection. The corrected suite also checks null precedence,
reserved missing aliases, and defensive argument copying.

Ownership tests cover shutdown after successful and failed startup, explicit
external ownership, and a queued async client call released after shutdown.
The caller's supplied executor remains usable. These tests do not claim that
the middleware drains business futures or bounds an arbitrary custom resolver's
shutdown time. Upstream Java-client timing-test sensitivity remains a separate
follow-up in the [readiness plan](public-readiness-plan.md#validation-boundaries).
