# Java client 3.0 compatibility pass

> Historical preparation record. Current dependency, registry, and publication
> instructions are in [the release runbook](releasing.md) and the README.

These are historical migration notes for existing checkouts. New users should
start with the [README](../README.md) and [configuration reference](configuration.md).

This migration preceded the public source release; it was not a package release.
Spring development uses `2.0.0-SNAPSHOT` and the
Java client uses `3.0.0-SNAPSHOT`, pinned to public commit
`fb0b26f1e514188a569eaf8fab5685c562111bac` in all build workflows.

## Configuration contract

```yaml
ratelimitly:
  enabled: true
  api-key: ${RATELIMITLY_API_KEY}
```

The API key is the only required connection setting. The Java client derives
discovery from its API-key ID. `ratelimitly.dns-name` is an optional override
for tests or host-managed discovery; a `DnsResolver` bean remains supported.

This is intentionally breaking: `credential`, `tenant-dns-name`,
`client.timeout`, and the unused `client.server-stability-threshold` are
removed. Use `api-key`, optional `dns-name`, and `client.dns-timeout` instead.
Unknown RateLimitly properties fail binding rather than silently accepting old
configuration. `client.debug` controls Spring integration logging only; it
does not enable logging in the standalone client.

The obsolete `client.steering-feedback` and `client.ignore-steering-feedback`
properties are also removed and rejected. The Java client manages transport
behavior internally; no replacement Spring settings are needed.

`#apiKeyId` replaces the old `#tenant` expression variable. It is the unsigned
decimal identifier decoded from the configured API key, never the credential
or its secret. It is empty when an application supplies its own client bean
without configuring an API key.

`#apiKeyId` is reserved: method arguments, path variables, and query parameters
cannot replace it. A same-named method argument remains accessible by position
(`#p0` or `#a0`, using its actual index), and HTTP values remain accessible
through `#pathVariables['apiKeyId']` or `#queryParams['apiKeyId']`.

## HA policy

Spring maps the complete Java `RequestPolicy`: unit, replay count, fixed/linear/
exponential schedule, final receive units, and completion delivery. Defaults
remain the Java defaults: 20 ms, one replay, fixed one-unit rounds, one final
receive unit, and completion delivery enabled (60 ms total horizon).

For example, a conservative fixed policy is:

```yaml
ratelimitly:
  client:
    dns-timeout: 1s
    request-policy:
      unit: 25ms
      replay-count: 3
      final-receive-units: 1
      completion-delivery: true
      schedule:
        kind: fixed
        initial-units: 1
```

Its deduplication horizon is 125 ms. The client validates the derived horizon
against the API-key quota during construction. For linear/exponential
schedules, `growth` means the step/factor and `max-units` is the cap, in that
order in the Java factory calls. Durations must be positive whole milliseconds
(or whole seconds for the DNS refresh interval); they are not silently rounded.

See the public client [configuration](https://github.com/ratelimitly-com/rl-java-client/blob/main/docs/configuration.md)
and [request-policy](https://github.com/ratelimitly-com/rl-java-client/blob/main/docs/request-policy.md)
documents for the underlying behavior.

## Ownership and request outcomes

Spring creates one reusable client and closes it on context shutdown. An
application-provided client bean overrides the default factory. The default
client owns its virtual-thread asynchronous executor; applications needing a
different executor can provide a client constructed with the Java builder.

Method and servlet enforcement must distinguish grant, rejection, and client
failure. Guard-only policies must reach the client; absence of resources does
not mean absence of protection. An empty resource request is local success in
the Java client. Existing automatic reporting behavior is unchanged by this
migration and will be reviewed separately during the Spring audit.

## Build and verification

Until `3.0.0` is available on Maven Central, clone the public Java client,
check out the pin above, and run `mvn -B -ntp clean install` there. Then run
`mvn -B -ntp clean verify` in this repository. No live API key or production
server is required. CI installs the same public source pin without a private
cross-repository credential and runs all tests on Linux JDK 21/25, macOS JDK
21, and Windows JDK 21.

The compatibility checks cover configuration binding, API-key-derived DNS,
custom DNS, policy mapping and quota rejection, bean ownership, real-client
loopback requests, guards, admission outcomes, and the sample application.

After client publication, replace the snapshot dependency with `3.0.0`, remove
the source installation from CI, and verify a clean Maven-Central-only build.
The Spring source is public, but neither package is published by this migration.
