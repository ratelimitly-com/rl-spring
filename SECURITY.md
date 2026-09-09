# Security

Report suspected vulnerabilities privately to
[wojciech@ratelimitly.com](mailto:wojciech@ratelimitly.com). Do not put a
vulnerability report, real API key, or authenticated traffic capture into a
public issue or pull request.

Include the affected commit/version, Java and Spring Boot versions, operating
system, redacted configuration, and a minimal reproduction. Use synthetic
credentials and local fixtures where possible. The integration is currently
an unreleased development snapshot; public source availability does not imply
a stable package release or support matrix.

## Credentials and logs

Treat the complete API key as a secret. Supply it through a secret manager or
a narrowly scoped environment variable, not source code or command-line
arguments. Redact configuration dumps and diagnostic attachments. Client-side
redaction does not protect arbitrary application logging or property dumps.

Built-in adapter debug logs contain counts, outcome kinds, and numeric
timing/server metadata; they omit request identifiers and exception text.
Default HTTP unavailability responses use a generic 503 message. This does
not sanitize custom handlers, application/framework logs, configuration dumps,
or error pages. Do not expose configuration, stack traces, or diagnostic
endpoints without suitable access controls.

The Java client's [security guide](https://github.com/ratelimitly-com/rl-java-client/blob/main/SECURITY.md)
describes credential/network protection choices. Synthetic NONE credentials
in tests are not production examples.

## Application trust boundaries

RateLimitly admission protects resource usage; it is not application
authentication or authorization. A resource grant is not proof that a caller
is entitled to access data or perform a business action.

SpEL expressions in annotations and policy templates must be trusted
application definitions. Do not accept expression source from HTTP parameters
or other untrusted input. Prefer explicit argument/map references and bounded
resource names. Unbounded path/argument-derived names and labels can exhaust
cardinality quotas or expose personal data.

See [security boundaries and ownership](docs/security-and-ownership.md) for
reserved expression variables, deterministic collision precedence, blank-guard
rejection, and client shutdown responsibilities. This is not a SpEL sandbox.

Choose fail-open or fail-closed deliberately. The current default is
fail-open for client request failures; that permits execution without a
usable decision. Unknown delivery outcomes are not confirmed rejections.

Consult the [current limitations](docs/usage.md#known-limitations)
before enabling adapters. In particular, WebFlux protection is not implemented.
The [publication notes](docs/public-readiness-plan.md) distinguish completed
source preparation from ongoing security maintenance and package publication.
Neither an audit nor a scanner guarantees that software has no vulnerabilities.
