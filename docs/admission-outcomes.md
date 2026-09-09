# Admission outcomes and method boundaries

This contract is covered by the integration's admission and method-boundary tests.
Admission accounts for protected work; measurement and local metrics must not
turn that work's result into a different result.

## Outcome contract

- A grant executes the protected operation once. Its value or original
  application exception is preserved, including exceptional async completion.
- A rejection never executes the operation, even in fail-open mode. With the
  default handlers it is HTTP 429 or `RateLimitDeniedException`.
- A client request failure executes the operation only in fail-open mode.
  Fail-closed defaults to HTTP 503 or `RateLimitUnavailableException`.
- For declared `CompletionStage` or `CompletableFuture` return types, default
  rejection/fail-closed outcomes are failed futures. Admission still blocks
  the caller. An arbitrary custom future subtype cannot be constructed by the
  adapter: if a failed `CompletableFuture` is not assignable to the declared
  type, the admission exception is thrown synchronously instead.
- A returned application stage remains the returned stage, so its identity,
  result, exception, and cancellation behavior are not replaced by a reporting
  stage. Observation follows the original completion; it does not resubmit work.
  A caller observing completion need not wait for the reporting callback to
  finish. Cancellation measures the future's lifetime, not proof that underlying
  application work stopped. If a custom stage refuses callback registration,
  return it unchanged and omit the unavailable measurement.
- Null-policy bypass and fail-open paths do not emit automatic latency reports.
  Granted work can report on success, application failure, or cancellation,
  once per invocation when reporting and guards are configured.

Automatic latency reporting and local observation are best effort. Checked
client reporting failures and ordinary runtime failures in reporting or
observation must not prevent admitted work, mask application errors, change a
completed value, or trigger a second reporting attempt. Fatal JVM `Error`s are
not treated as ordinary operational failures. This does not suppress errors
from policy/label resolution, explicit application client calls, or custom
denial/failure handlers.

## Nested calls and placement

Each call through a protected Spring proxy is an independent admission.
An outer grant does not exempt a call to another protected bean, and an inner
rejection does not refund the outer grant. Class and method annotations on the
same invocation select one policy, with the method annotation taking precedence;
they must not install two admission checks. Self-invocation still bypasses
Spring proxy advice; it is not an independently protected boundary.

Automatic configuration reuses an existing advisor-aware Spring auto-proxy
creator rather than installing a competing creator. The RateLimitly advisor
is infrastructure advice so Spring's infrastructure-only creator also applies
it, including an intentional bean override named `rateLimitlyMethodAdvisor`.
Registration waits until Boot's AOP factory post-processing so a creator
registered there is not overlooked. Applications replacing the creator with
one that intentionally excludes this advisor own that selection.

The default method advisor has Spring's lowest precedence. Advisors with a
smaller order run before it on entry. Equal order is not a reliable ordering
contract. Authentication, authorization, retry, transaction, and other advice
must be arranged deliberately by the application. The middleware cannot
guarantee that no later application check will reject admitted work. Configure
one admission boundary around the work you intend to consume, and test the
application's actual advisor chain.

See Spring's [proxy semantics](https://docs.spring.io/spring-framework/reference/core/aop/proxying.html)
and [advice ordering](https://docs.spring.io/spring-framework/reference/core/aop/ataspectj/advice.html#aop-ataspectj-advice-ordering).
HTTP dispatch boundaries are described separately in [HTTP lifecycle](servlet-lifecycle.md).
Automatic reporting remains whole-operation timing to the configured guard
trackers, not per-dependency instrumentation. Expression/security, ownership,
and optional-dependency checks remain separate readiness work.

## Regression evidence

On baseline `84cf8a7`, the first 38 outcome cases produced 11 assertion failures
and six errors from the injected runtime reporting/observation faults. The
seven proxy-boundary cases produced two assertion failures: an existing
general advisor creator caused two admission checks, and the infrastructure
creator coexisted with an unnecessary second creator. Documentation and these
tests precede the implementation fix. They use synthetic in-process clients;
no production credentials, DNS, or RateLimitly server is involved.

Follow-up tests exercise nested async calls, reporting switches, observation
failures during denial/fail-closed handling, and a custom stage refusing
observation. The sample additionally requires exactly one auto-proxy creator
and exactly one HTTP plus one method check. This caught Boot's later creator
registration during implementation; a further red test protects intentional
named advisor overrides with infrastructure-only creators.

The full local reactor passes 146 tests, including 53 new outcome/proxy cases.
PR review, exact-head cross-platform CI, and exact merged-main validation
remain gates before marking this readiness step complete.
