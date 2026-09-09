# Spring Boot Integration Requirements

> Historical design proposal, not the current supported API. In particular,
> the WebFlux and broader observability requirements below are not implemented
> promises. For actual behavior use the [README](README.md),
> [usage guide](docs/usage.md), and [configuration reference](docs/configuration.md).
> Remaining work is tracked by the [public-readiness plan](docs/public-readiness-plan.md).

This document defines the requirements for the `rl-spring-boot` repo, a Spring Boot integration layer built on top of the standalone Java RateLimitly client.

The goal is to make Ratelimitly feel native in Spring Boot applications without pushing Spring-specific concerns into the base Java client.

## 1. Scope and Boundaries

- `rl-spring-boot` is a separate repo from the standalone Java client.
- `rl-spring-boot` depends on the Java client and Spring Boot libraries.
- The standalone Java client remains responsible for:
  - UDP wire protocol,
  - DNS discovery,
  - authentication,
  - HA response collection and selection,
  - request policy,
  - diagnostics.
- `rl-spring-boot` is responsible for:
  - Spring Boot auto-configuration,
  - Spring property binding,
  - HTTP and method-execution interception and enforcement,
  - Micrometer/Actuator integration,
  - invocation-to-policy mapping,
  - fail-open and fail-closed application behavior.

## 2. Primary Goal

Provide a Spring Boot integration that lets an application:

- configure RateLimitly through standard Spring properties,
- protect MVC/WebFlux traffic and arbitrary Spring-managed method execution with minimal code,
- choose how enforcement failures map to HTTP or method-call behavior,
- emit useful operational telemetry,
- stay compatible with the standalone Java client without forking client behavior.

## 3. Target Usage Modes

The Spring integration should support three usage modes:

### 3.1 Auto-Configured Client Only

Applications may want only a preconfigured `RateLimitlyClient` bean and will build their own filters or service-layer usage.

### 3.2 Declarative Protection

Applications may want protection with minimal custom code through framework-provided filters, interceptors, AOP, or annotations on controllers and services.

### 3.3 Programmatic Policy Control

Applications may want to compute resource requests, guards, labels, or failure behavior dynamically based on invocation context.

## 4. Recommended Repo Structure

The recommended initial module layout for `rl-spring-boot` is:

- `ratelimitly-spring-boot-autoconfigure`
  - Boot auto-configuration and property binding.
- `ratelimitly-spring-boot-starter`
  - Thin dependency convenience module.
- `ratelimitly-spring-boot-test`
  - Test helpers and fixtures for integration tests.

If the first iteration needs to stay smaller, `autoconfigure` and `starter` may be combined initially, but the design should still keep those concerns separable.

## 5. Auto-Configuration Requirements

The Spring Boot repo must provide auto-configuration for a shared `RateLimitlyClient`.

Requirements:

- Create the client as a singleton bean.
- Bind configuration from Spring properties into the Java client config model.
- Make bean creation conditional on a feature flag such as `ratelimitly.enabled`.
- Allow user-supplied `RateLimitlyClient` beans to override auto-configuration.
- Close the client cleanly during application shutdown.
- Avoid forcing HTTP or method interception just because the client bean exists.

## 6. Configuration Requirements

The Spring properties model should cover:

- Bech32 API key (the only required connection setting),
- optional DNS name override,
- DNS timeout settings,
- retry and HA policy,
- DNS refresh settings,
- default fail-open vs fail-closed mode,
- default metrics label mode,
- servlet/WebFlux integration enablement,
- method-execution interception enablement,
- observability enablement.

The Bech32 API key carries its `key_id`, authentication method, secret material,
and API-key quotas. Normal discovery is derived from the key ID by the client.

The properties surface should stay close to the standalone Java client config where possible. Spring-specific flags should live alongside, not inside, the base client model.

## 7. Servlet Stack Requirements

The repo must support Spring MVC / servlet applications.

Requirements:

- Provide a servlet integration component, likely a `Filter`, `HandlerInterceptor`, or both.
- Support protecting selected routes rather than forcing all routes through RateLimitly.
- Allow custom extraction of:
  - resource definitions,
  - latency guards,
  - metrics labels,
  - application-defined subject dimensions if needed later.
- Support post-handler latency reporting where configured.
- Preserve normal servlet request flow and avoid breaking existing filters/security layers.

## 8. Reactive Stack Requirements

The repo must support Spring WebFlux applications.

Requirements:

- Provide a reactive interception component, likely a `WebFilter`.
- Use the Java client async API rather than blocking event-loop threads.
- Allow the same logical policy model as the servlet stack.
- Support reactive latency measurement and reporting around handler execution.
- Avoid hidden thread blocking or scheduler assumptions.

## 9. Method Execution Requirements

The repo must support protection of arbitrary Spring-managed method execution, not only HTTP request handling.

Requirements:

- Provide a method-interception mechanism, likely via Spring AOP or an equivalent proxy-based approach.
- Allow annotation-driven protection of service, component, and controller methods.
- Support programmatic policy resolution from method signature, arguments, bean type, annotations, and invocation context.
- Allow method-level latency measurement and reporting where configured.
- Make fail-open and fail-closed semantics explicit for intercepted method calls.

## 10. Policy Mapping Requirements

The Spring layer must provide a clean way to map incoming requests or method invocations to RateLimitly decisions.

The integration should support:

- global default policy,
- path-based policy selection,
- HTTP-method-aware policy selection,
- annotation-driven policy selection,
- method-signature-based policy selection,
- programmatic policy resolvers.

Each policy should be able to define:

- one or more resource requests,
- optional latency guards,
- optional metrics label generation,
- fail-open vs fail-closed behavior,
- denial response mapping for HTTP integrations where applicable,
- whether latency reporting is enabled.

## 11. Annotation and Resolver Requirements

The Spring Boot repo should support both declarative and programmatic customization.

Recommended extension points:

- an annotation such as `@RateLimited` for common endpoint and method use cases,
- a policy resolver interface for dynamic per-request or per-invocation policy resolution,
- a label resolver interface for metrics-label generation,
- a denial handler interface for HTTP response generation,
- a failure handler interface for transport or timeout errors,
- a method-failure handler interface for non-HTTP invocations when fail-closed is selected.

Annotations are optional for MVP, but resolver interfaces should exist early so the integration does not get trapped in a purely annotation-driven design.

## 12. Enforcement Semantics

The repo must make enforcement behavior explicit.

It should define:

- what constitutes a deny result,
- what constitutes a transport/config/runtime failure,
- how deny results map to HTTP responses,
- how deny results map to intercepted method behavior,
- how client failures map to HTTP responses when fail-closed,
- how client failures map to intercepted method behavior when fail-closed,
- how requests or method executions are allowed through when fail-open,
- how these defaults can be overridden per policy.

Recommended defaults for the first design pass:

- deny result: HTTP `429 Too Many Requests`,
- fail-closed transport error: HTTP `503 Service Unavailable`,
- fail-open transport error: request proceeds and a counter is emitted.

Recommended defaults for intercepted methods:

- deny result: throw `RateLimitDeniedException`,
- fail-closed transport/config/runtime error: throw `RateLimitUnavailableException`,
- fail-open transport/config/runtime error: proceed with method execution and emit a counter,
- async/reactive method denial or fail-closed error: surface the same exception through exceptional completion,
- the integration should not define a default synthetic fallback return value.

The exact defaults can change later, but they should be documented and configurable.

## 13. Metrics Label Requirements

Metrics labels in Spring should support both HTTP-derived and method-derived context.

The Spring integration should support label generation from:

- HTTP method,
- route template or handler mapping pattern,
- application-defined policy name,
- optional controller or handler identifiers,
- rate-limited method identifiers,
- method signature or annotation metadata,
- application-provided invocation context.

Requirements:

- prefer stable route-template-based labels over raw URLs,
- prefer stable method identifiers over argument-derived labels,
- avoid unbounded label cardinality from path parameters,
- avoid unbounded label cardinality from arbitrary method arguments,
- allow applications to override the label format,
- pass labels to the Java client only when configured.

## 14. Observability Requirements

The repo should integrate with Micrometer and fit Spring Boot Actuator expectations.

It should expose:

- counters for allow, deny, fail-open, and fail-closed outcomes,
- counters for timeout, DNS, protocol, auth, and transport failures,
- timers for client round-trip duration,
- timers for protected handler or method execution where latency reporting is enabled,
- retry counters,
- DNS refresh counters,
- optional gauges or snapshots for discovered server count and selected server diagnostics.

Observability must be possible without requiring the application to inspect internal Java client objects directly.

## 15. Security and Data Handling Requirements

The Spring repo must treat credentials and request metadata carefully.

Requirements:

- avoid logging full Bech32 secrets,
- avoid emitting raw auth material into metrics or error messages,
- make debug logging opt-in,
- ensure route labels do not accidentally include raw user identifiers or query strings unless explicitly configured,
- ensure method-derived labels do not accidentally include raw argument values unless explicitly configured.

## 16. Testing Requirements

The Spring Boot repo should have integration-oriented tests for:

- property binding,
- auto-configuration backoff when user beans are present,
- servlet interception,
- WebFlux interception,
- method-execution interception,
- fail-open behavior,
- fail-closed behavior,
- denial response mapping,
- method-failure mapping,
- custom resolver hooks,
- Micrometer metrics emission,
- client shutdown on application context close.

The tests should not rely solely on mocks. At least part of the suite should exercise the integration against a controllable fake or test implementation of the Java client.

## 17. MVP Recommendation

The recommended MVP for `rl-spring-boot` is:

1. Auto-configure a shared `RateLimitlyClient`.
2. Provide configuration properties and a feature flag.
3. Support method-execution protection and servlet-stack protection first.
4. Support fail-open and fail-closed behavior explicitly.
5. Support stable programmatic metrics labels, including route-template-based labels for HTTP integrations.
6. Emit Micrometer counters and timers.
7. Add WebFlux support immediately after the servlet path is stable, or in MVP if the team is prepared to maintain both from day one.

## 18. Non-Goals for the First Iteration

The first iteration should avoid:

- embedding protocol logic in the Spring repo,
- requiring AOP for all integrations,
- hard-wiring one annotation model as the only extension mechanism,
- mixing Boot auto-configuration concerns into the standalone Java client,
- opinionated gateway-wide policy DSLs before the lower-level hooks are stable.

## 19. Recommended Next Deliverables

After this document, the next useful artifacts are:

1. a concrete Spring properties model,
2. a Java API sketch for the resolver and handler extension points,
3. a proposed module/package layout for `rl-spring-boot`,
4. an MVP implementation plan covering method execution and servlet path first, then WebFlux.
