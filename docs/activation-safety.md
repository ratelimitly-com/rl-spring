# Activation safety

This defines the startup contract tested by `ActivationSafetyTest`.
See the [usage guide](usage.md) for application setup.

## Contract

- `ratelimitly.enabled=true` is required for all automatic integration beans,
  including when the application supplies its own client. An absent or false
  flag must not enable method interception or HTTP admission. Application-owned
  beans and direct client calls remain under the application's control.
- The servlet and method flags select independent integrations. Turning one
  off must not turn off the other. A non-web application can use method
  protection without the servlet API.
- `observation.enabled=false` disables the default recorder's Micrometer
  activity, not admission. The adapters still receive a no-op recorder.
  Without a registry bean the default recorder is also a no-op. An explicitly
  supplied recorder overrides the default and is application-configured.
- Unsupported `webflux.*` settings are removed. With RateLimitly enabled,
  strict property binding rejects them rather than silently promising reactive
  protection. This does not add WebFlux support.
- Custom policy, label, and failure-handler beans must override only the
  matching integration. An unrelated method interceptor must not be selected
  as the RateLimitly admission advice, even if marked `@Primary`. To override
  that advice intentionally, supply a bean named `rateLimitlyMethodInterceptor`;
  the application then owns its admission behavior.

## Verification approach

Against main `4b9a894`, the first 16 test cases produced eight assertion
failures (no test errors):

| Finding | Reproduction | Required correction |
| --- | --- | --- |
| Global opt-in bypass | A supplied client triggers adapters without `enabled=true`; startup fails without support beans, or enables protection if they are supplied. | Gate every automatic integration on the global flag. |
| Metrics disablement breaks startup | `observation.enabled=false` leaves enforcers without a recorder. | Supply the default recorder in no-op mode. |
| Unsupported protection accepted | Three `webflux.*` configurations bind successfully but install no protection. | Remove the inert properties and empty configuration. |
| Ambiguous admission advice | An unrelated `MethodInterceptor` makes advisor injection ambiguous. | Select the named RateLimitly interceptor explicitly. |

The follow-up primary-advice test also fails without the qualifier: an
unrelated pass-through interceptor marked `@Primary` replaces the admission
advice, so the protected call returns without asking the rejecting client.
This is an admission bypass, not just a startup problem.

The expanded suite has 20 activation cases covering the fixes and surrounding
contracts, including independent latency reporting with metrics disabled,
named advice overrides, both directions of typed resolver overrides, and
servlet-free method protection. At this first activation step, the full Maven
reactor passed 59 tests; the [HTTP lifecycle work](servlet-lifecycle.md) expands
that coverage.
The activation cases use a stub client and local test contexts: no DNS, real
credentials, or running server is required. Cross-platform PR CI and exact
merged-main CI remain gates before completing this readiness step.

This work does not complete the behavior/security audit. HTTP dispatch and
completion have a [separate contract and regression suite](servlet-lifecycle.md).
Nested method calls, the full failure/reporting matrix, measurement suitability,
expression/logging boundaries, and full optional-dependency/ownership checks
remain in issue #9. No publication or visibility change is part of this change.
