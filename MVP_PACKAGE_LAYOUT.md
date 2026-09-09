# rl-spring MVP Package Layout

> Historical architecture sketch, not a list of implemented modules or APIs.
> Some proposed classes and signatures below do not exist in the current code.
> Start with the [README](README.md), [usage guide](docs/usage.md), and
> [configuration reference](docs/configuration.md). The
> [public-readiness plan](docs/public-readiness-plan.md) records remaining work.

This document turns the Spring requirements into a concrete repo and package sketch for the first implementation pass.

The design keeps the same separation that worked well in `rl-nginx`:

- the standalone Java client owns UDP, DNS discovery, auth, request policy, and wire encoding,
- `rl-spring` owns Spring property binding, auto-configuration, interception, mapping, and Micrometer integration,
- policy mapping is explicit and testable instead of being hidden inside filters or annotations.

## Module Layout

Recommended Maven multi-module layout:

- `ratelimitly-spring-boot-parent`
  - aggregator/root POM only
- `ratelimitly-spring-boot-autoconfigure`
  - Spring Boot auto-configuration
  - property binding
  - servlet support
  - method-interception support
  - WebFlux support
  - Micrometer integration
  - SPI types
- `ratelimitly-spring-boot-starter`
  - depends on `autoconfigure`
  - convenience starter for applications
- `ratelimitly-spring-boot-test`
  - fake client
  - test fixtures
  - assertion helpers

For the first scaffold, `autoconfigure` and `starter` can be created immediately, while `test` can follow in the same repo once the first integration tests are added.

## Root Package

Use:

`com.ratelimitly.spring`

Subpackages:

- `com.ratelimitly.spring.autoconfigure`
- `com.ratelimitly.spring.properties`
- `com.ratelimitly.spring.policy`
- `com.ratelimitly.spring.servlet`
- `com.ratelimitly.spring.method`
- `com.ratelimitly.spring.webflux`
- `com.ratelimitly.spring.observation`
- `com.ratelimitly.spring.support`
- `com.ratelimitly.spring.test`

## Package Responsibilities

### `autoconfigure`

Owns Boot wiring only.

Suggested classes:

- `RateLimitlyAutoConfiguration`
- `RateLimitlyServletAutoConfiguration`
- `RateLimitlyMethodAutoConfiguration`
- `RateLimitlyWebFluxAutoConfiguration`
- `RateLimitlyObservationAutoConfiguration`
- `RateLimitlyBeanNames`

Responsibilities:

- create a shared `RateLimitlyClient` bean when enabled,
- back off when the application provides its own client bean,
- register default SPI beans only when missing,
- enable servlet, method, and WebFlux integrations independently,
- close the client on context shutdown.

### `properties`

Owns Spring-side configuration objects, not protocol types.

Suggested classes:

- `RateLimitlyProperties`
- `RateLimitlyClientProperties`
- `RateLimitlyServletProperties`
- `RateLimitlyMethodProperties`
- `RateLimitlyWebFluxProperties`
- `RateLimitlyObservationProperties`
- `FailMode`

Suggested top-level property model:

- `ratelimitly.enabled`
- `ratelimitly.api-key`
- `ratelimitly.dns-name` (optional override)
- `ratelimitly.client.*`
- `ratelimitly.servlet.*`
- `ratelimitly.method.*`
- `ratelimitly.webflux.*`
- `ratelimitly.observation.*`

The Spring properties should map into `RateLimitlyClientConfig` rather than redefining protocol details.

### `policy`

Owns the adapter-side mapping from Spring invocation context to Java-client requests.

Suggested classes:

- `RateLimitlyPolicy`
- `RateLimitlyPolicyResolver<C>`
- `HttpRateLimitlyPolicyResolver`
- `MethodRateLimitlyPolicyResolver`
- `RateLimitlyLabelResolver<C>`
- `HttpRateLimitlyLabelResolver`
- `MethodRateLimitlyLabelResolver`
- `RateLimitlyLatencyReporter<C>`
- `RateLimitlyFailureHandler<C>`
- `HttpDenialHandler`
- `MethodDenialHandler`

Suggested `RateLimitlyPolicy` contents:

- `String policyName`
- `List<ResourceRequest> resources`
- `List<LatencyGuard> guards`
- `boolean reportLatency`
- `FailMode failMode`
- `boolean emitMetricsLabel`
- optional denial metadata for HTTP handling

This package is the Spring equivalent of the nginx zone/guard/label mapping layer.

### `servlet`

Owns Spring MVC / servlet interception.

Suggested classes:

- `RateLimitlyServletFilter`
- `RateLimitlyHandlerInterceptor`
- `ServletRequestContext`
- `ServletRateLimitEnforcer`
- `DefaultHttpRateLimitlyPolicyResolver`
- `DefaultHttpRateLimitlyLabelResolver`

Responsibilities:

- build a stable request context from servlet inputs,
- resolve a policy,
- call the shared `RateLimitlyClient`,
- apply deny/failure behavior,
- optionally report post-handler latency.

The default label resolver should prefer route templates and handler mappings over raw URLs.

### `method`

Owns Spring-managed method interception.

Suggested classes:

- `@RateLimited`
- `RateLimitlyMethodInterceptor`
- `MethodInvocationContext`
- `DefaultMethodRateLimitlyPolicyResolver`
- `DefaultMethodRateLimitlyLabelResolver`
- `RateLimitDeniedException`
- `RateLimitUnavailableException`

Responsibilities:

- resolve policy from method signature, bean type, and annotation metadata,
- enforce deny and fail-open/fail-closed behavior,
- measure and optionally report invocation latency,
- avoid deriving labels from arbitrary argument values by default.

### `webflux`

Owns reactive HTTP integration.

Suggested classes:

- `RateLimitlyWebFilter`
- `WebFluxRequestContext`
- `WebFluxRateLimitEnforcer`
- `DefaultWebFluxRateLimitlyPolicyResolver`

Responsibilities:

- use `checkRateLimitAsync(...)`,
- keep blocking off the event loop,
- preserve the same policy model as servlet,
- report latency after reactive completion.

This can come after servlet and method support, but the package boundary should exist from day one.

### `observation`

Owns Micrometer integration.

Suggested classes:

- `RateLimitlyObservationRecorder`
- `RateLimitlyMeterNames`
- `DefaultRateLimitlyTagsProvider`

Suggested counters:

- `ratelimitly.requests.allowed`
- `ratelimitly.requests.denied`
- `ratelimitly.requests.fail_open`
- `ratelimitly.requests.fail_closed`
- `ratelimitly.requests.timeout`
- `ratelimitly.requests.transport_error`
- `ratelimitly.requests.dns_error`
- `ratelimitly.requests.protocol_error`
- `ratelimitly.requests.auth_error`
- `ratelimitly.latency_reports.sent`
- `ratelimitly.latency_reports.failed`

Suggested timers:

- `ratelimitly.client.round_trip`
- `ratelimitly.handler.execution`
- `ratelimitly.method.execution`

### `support`

Owns small shared helpers that should not depend on servlet, WebFlux, or AOP directly.

Suggested classes:

- `RateLimitDecisionEvaluator`
- `ClientConfigMapper`
- `SanitizedLogging`
- `ClockSupport`

This package should stay narrow. Do not let it turn into a dumping ground.

### `test`

Owns reusable testing infrastructure.

Suggested classes:

- `FakeRateLimitlyClient`
- `TestPolicyResolvers`
- `ServletTestSupport`
- `MethodTestSupport`
- `WebFluxTestSupport`

The fake client should be deterministic and support:

- allow,
- deny,
- timeout,
- transport failure,
- async completion,
- latency report capture.

## First Implementation Slice

The first scaffold should implement only the minimal path needed to prove the architecture:

1. root Maven parent
2. `autoconfigure` module
3. `starter` module
4. `RateLimitlyProperties`
5. shared `RateLimitlyClient` auto-configuration
6. `RateLimitlyPolicy` plus resolver interfaces
7. servlet filter or interceptor path
8. method interceptor path
9. Micrometer counters for allow, deny, fail-open, fail-closed

WebFlux should have a reserved package and auto-configuration class name, even if the first code pass lands after servlet and method interception.

## Suggested SPI Contracts

These are the most important adapter-side contracts to stabilize early:

```java
public interface RateLimitlyPolicyResolver<C> {
    RateLimitlyPolicy resolve(C context);
}

public interface RateLimitlyLabelResolver<C> {
    String resolveLabel(C context, RateLimitlyPolicy policy);
}

public interface RateLimitlyFailureHandler<C> {
    void onFailure(C context, Exception failure, FailMode failMode) throws Exception;
}
```

For HTTP denial handling, keep denial separate from transport/runtime failure:

```java
public interface HttpDenialHandler {
    void handleDenied(HttpServletRequest request, HttpServletResponse response,
                      RateLimitDecision decision) throws IOException;
}
```

## Why This Shape

This layout keeps `rl-spring` close to the successful nginx pattern:

- a small config surface,
- an explicit mapping layer,
- explicit allow/deny/failure semantics,
- post-completion latency reporting,
- observability owned by the integration layer,
- strict separation from wire-level client behavior.

That should make the first scaffold straightforward without trapping the repo in an annotation-only or servlet-only design.
