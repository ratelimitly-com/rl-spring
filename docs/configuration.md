# Spring configuration reference

All properties below have the `ratelimitly.` prefix. This reference describes
the current implementation, including its startup checks and documented
integration limits.

## Select an integration

For annotated methods only, use the README configuration: enable RateLimitly,
provide the API key, and set `servlet.enabled=false`.

For MVC admission only:

```yaml
ratelimitly:
  enabled: true
  api-key: ${RATELIMITLY_API_KEY}
  default-fail-mode: closed
  method:
    enabled: false
```

For a shared client without automatic HTTP or method checks:

```yaml
ratelimitly:
  enabled: true
  api-key: ${RATELIMITLY_API_KEY}
  servlet:
    enabled: false
  method:
    enabled: false
```

Inject `RateLimitlyClient` into your own application code. It is created once
and closed when the Spring context closes, not after each operation. An
application-provided client bean overrides the default factory; configure
that bean's lifecycle explicitly.

The global `enabled=true` opt-in is required for all automatic integrations,
even with an application-provided client. With it absent or false, the starter
does not install admission adapters or its default metrics recorder. It does
not disable direct calls to an application-owned client or remove user beans.

For the raw servlet filter instead of the MVC interceptor, set
`servlet.filter-enabled=true` and `servlet.interceptor-enabled=false`.
Enabling both is rejected at startup: they are alternative HTTP admission
boundaries, not two policies to combine. The raw filter covers request, async,
error, forward, and include dispatches. Async continuation preserves the
original admission, while internal targets retain their own protection;
see [HTTP lifecycle](servlet-lifecycle.md). Neither adapter replaces application
authentication or authorization.

## Connection and outcome settings

| Property | Default | Meaning |
| --- | --- | --- |
| `enabled` | `false` | Global opt-in for automatic client, admission, and observation configuration, also required with a supplied client. Use per-adapter flags to select enforcement. |
| `api-key` | Unset | Required for the default client factory. Supply through a secret manager or environment. |
| `dns-name` | Unset | Optional discovery override. Normally discovery is derived from the API key. A custom `DnsResolver` bean is also supported. |
| `default-fail-mode` | `open` | `open` executes work without a decision after a client failure; `closed` prevents it. Rejections remain rejections in either mode. |
| `emit-default-metrics-label` | `false` | Include the resolver's label in resource requests. This is independent of local Micrometer metrics. |
| `client.dns-timeout` | `1s` | DNS timeout, not the resource-request wait policy. Positive whole milliseconds. |
| `client.dns-refresh-interval` | `5m` | Discovery refresh interval. Positive whole seconds. |
| `client.debug` | `false` | Permit count/outcome/numeric diagnostics when the logger is also at DEBUG, without request identifiers or exception text. Does not enable standalone Java-client logging. |

Unknown properties fail binding when the properties bean is created. Removed
settings include `credential`, `tenant-dns-name`, `client.timeout`,
`client.server-stability-threshold`, `client.steering-feedback`, and
`client.ignore-steering-feedback`. Unsupported `webflux.*` settings are also
removed; delete them, including `webflux.enabled=false`. See the
[migration notes](java-client-v3-migration.md) if moving from an older checkout.

## Resource-request delivery policy

The Spring adapter uses the Java client's request policy without implementing
another HA algorithm. See the public [policy guide](https://github.com/ratelimitly-com/rl-java-client/blob/main/docs/request-policy.md)
for response selection, replay behavior, and completion delivery. These
resource-request retry settings do not turn latency reports into resource
requests or give them an admission response.

All names in this table have the additional `client.request-policy.` prefix:

| Property | Default | Meaning |
| --- | --- | --- |
| `unit` | `20ms` | Positive whole-millisecond time unit. |
| `replay-count` | `1` | Replay rounds after the initial transmission; zero means no replay. |
| `final-receive-units` | `1` | Receive-only time after transmission rounds; zero removes it. |
| `completion-delivery` | `true` | Best-effort delivery to still-missing servers before returning a decision. |
| `schedule.kind` | `fixed` | `fixed`, `linear`, or `exponential`, case-insensitive. |
| `schedule.initial-units` | `1` | Initial round duration in units. |
| `schedule.max-units` | `1` | Cap for linear/exponential schedules; unused for fixed schedules. |
| `schedule.growth` | `0` | Linear step or exponential factor; unused for fixed schedules. |

When changing to a growing schedule, set its growth and cap explicitly. The
Java client rejects invalid policies and horizons exceeding the API-key quota.
The complete policy horizon is also its deduplication TTL; it is not a bound
on unrelated DNS or application execution time.

A more conservative fixed policy than the defaults is:

```yaml
ratelimitly:
  client:
    request-policy:
      unit: 25ms
      replay-count: 3
      final-receive-units: 1
      completion-delivery: true
      schedule:
        kind: fixed
        initial-units: 1
```

This example has a 125 ms horizon: `(1 initial + 3 replay + 1 final) * 25 ms`.
The default fixed policy has a 60 ms horizon. Add these settings to an enabled
configuration; this fragment does not supply an API key or enable protection.

## Servlet and method settings

| Property | Default | Meaning |
| --- | --- | --- |
| `servlet.enabled` | `true` | With global opt-in, enable servlet adapter configuration when a client bean and servlet application are present. |
| `servlet.interceptor-enabled` | `true` | Use the MVC handler interceptor. |
| `servlet.filter-enabled` | `false` | Use the raw servlet filter across all dispatch types. Disable the interceptor; enabling both fails startup. |
| `method.enabled` | `true` | With global opt-in, enable proxy interception for applicable `@RateLimited` methods when a client bean exists. |
| `servlet.report-latency` | `true` | Permit reporting to guards in the resolved servlet policy. No reports are emitted by the guard-free default policy. |
| `method.report-latency` | `true` | Permit reporting to method guards, also controlled by the annotation's `reportLatency`. |

Both resolvers use these resource defaults:

| Suffix | `servlet.` default | `method.` default |
| --- | --- | --- |
| `default-bucket-prefix` | `http` | `method` |
| `default-window` | `1s` | `1s` |
| `default-rate-limit` | `1000` | `1000` |
| `default-tokens-requested` | `1` | `1` |

These are application defaults, not API-key quota values. Requests must still
fit the key. Use positive whole-millisecond windows. Method annotation
overrides and bucket naming are explained in [usage](usage.md#resource-names-and-definitions).

## Observability and unsupported settings

`observation.enabled` defaults to `true`. The default recorder uses an
application-provided `MeterRegistry`, if present, and otherwise is a no-op.
Set it to `false` to use a no-op default recorder even when a registry exists.
This does not disable admission or latency reports. No replacement bean is
required. An explicitly supplied `RateLimitlyObservationRecorder` overrides
the default; configure that custom recorder's behavior yourself.
The adapters isolate ordinary runtime observation failures from admission and
application outcomes, including failures from a custom recorder. Automatic
reporting is also best effort; see [outcome isolation](admission-outcomes.md).

Local counters include `ratelimitly.requests.allowed`, `.denied`, `.fail_open`,
`.fail_closed`, `.timeout`, `.transport_error`, `.dns_error`, `.protocol_error`,
and `.auth_error`, plus `ratelimitly.latency_reports.sent` and `.failed`.
Timers are `ratelimitly.client.round_trip`, `ratelimitly.handler.execution`,
and `ratelimitly.method.execution`. They count adapter activity, not unique
HTTP requests or remote server counters. The current round-trip timer records
successful client calls, not client-failure durations; `sent` reports mean
local sending succeeded, not acknowledged remote storage.

WebFlux enforcement and reporting are not implemented. The former
`webflux.enabled` and `webflux.report-latency` placeholders are removed:
when RateLimitly is enabled, these properties now fail strict binding instead
of silently configuring no protection. See the [activation contract](activation-safety.md).

Debug logging may include resource names, labels, URIs, method identity, and
failure messages. Review that data before enabling it. Consult [SECURITY.md](../SECURITY.md).
