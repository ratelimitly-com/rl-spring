# RateLimitly for Spring Boot

[RateLimitly](https://ratelimitly.com/) helps an application decide whether to
begin work. It can limit resource consumption and require that the expected
latency of a service is below a chosen threshold.

`rl-spring` brings those decisions into Spring Boot applications through method
annotations, Spring MVC interception, and a reusable Java client bean.

## Two independent operations

A **resource request** describes the work you want to do: a set of resource
consumptions and a set of latency guards. For example: “Get one token for
checkout, but only if inventory is expected to respond within 100 ms.” A grant
consumes the requested resources; a rejection consumes none. A request may
contain just guards, just resource consumptions, or both.

A **latency report** contributes measurements for future latency guards. For
example: “That call to inventory took 18 ms.” It does not request permission
or consume resources. An application may report latencies without making
resource requests, and may make resource requests without reporting latencies.

Failure to obtain a decision is **neither a grant nor a rejection**. It needs
a separate application failure policy. A timeout does not prove that the
request was never processed.

## Installation and first configuration

The first Maven registry release, **2.0.0**, is being prepared, using
`com.ratelimitly:ratelimitly-java-client:3.0.0`. Neither is published yet.
Publish the Java dependency first, then Spring. Once the
[Spring release](https://github.com/ratelimitly-com/rl-spring/releases) is available,
add the public GitLab Maven repository and starter to your application's POM:

```xml
<repositories>
  <repository>
    <id>ratelimitly-public</id>
    <url>https://gitlab.com/api/v4/projects/86375734/packages/maven</url>
    <releases><enabled>true</enabled></releases>
    <snapshots><enabled>false</enabled></snapshots>
  </repository>
</repositories>

<dependencies>
  <dependency>
    <groupId>com.ratelimitly</groupId>
    <artifactId>ratelimitly-spring-boot-starter</artifactId>
    <version>2.0.0</version>
  </dependency>
</dependencies>
```

No GitLab account or download token is needed. The same registry supplies the
transitive Java client, Spring parent POM, and autoconfigure module. Other
dependencies still resolve from Maven Central normally.

Source code and release downloads stay on GitHub. We chose GitLab's Free
registry after Sonatype classified these service clients as requiring a paid
publishing subscription. The MIT license is unchanged. See the
[publication runbook](docs/releasing.md) and [contributor build
instructions](CONTRIBUTING.md#build-and-test).

The integration targets Java 21 or newer and currently builds against Spring
Boot 4.0.5. It supports Spring MVC and Spring-managed method calls, not WebFlux
or Reactor interception.

Start with method protection only, so each annotated invocation below performs
one admission check. The independent reporting example performs no admission check:

```yaml
ratelimitly:
  enabled: true
  api-key: ${RATELIMITLY_API_KEY}
  default-fail-mode: closed
  servlet:
    enabled: false
```

Supply the API key through your environment or secret manager. Do not commit
it or put it on a command line. No DNS setting is normally required.

This example explicitly chooses **fail-closed**: when a decision cannot be
obtained, the protected work does not start. The library's current default is
**fail-open**. See [outcomes and failure handling](docs/usage.md#outcomes).

## Three small examples

These classes belong in your application's component-scan path. Call annotated
methods through an injected Spring bean; calling them through `new` or through
another method on the same object does not provide proxy-based protection.

### Request one token

“Before checkout, get one token from a bucket allowing 100 tokens per second.”

```java
import com.ratelimitly.spring.method.RateLimited;
import org.springframework.stereotype.Service;

@Service
public class CheckoutService {
    @RateLimited(
        context = "checkout", // Bucket suffix: the default full name is method:checkout.
        rate = 100,           // Tokens allowed in the window.
        window = "1s",        // Rate-counter window.
        tokensRequested = 1   // Tokens consumed by this invocation.
    )
    public void checkout() {
        // Perform the protected work here, after admission.
    }
}
```

A rejection prevents the method body from running and raises
`RateLimitDeniedException`. With the fail-closed configuration above, a client
request failure instead raises `RateLimitUnavailableException`.

### Report one service latency

“Record that a call to inventory took 18 ms.” Call `record(18)` on this bean
after measuring the actual inventory call. Reporting does not require an
annotated method or any previous grant.

```java
import com.ratelimitly.LatencyReport;
import com.ratelimitly.RateLimitlyClient;
import com.ratelimitly.RateLimitlyException;
import com.ratelimitly.ServiceLatencyReport;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class InventoryMeasurements {
    private final RateLimitlyClient client;

    public InventoryMeasurements(RateLimitlyClient client) {
        this.client = client; // Reuse the client managed by Spring.
    }

    public void record(long durationMs) throws RateLimitlyException {
        client.reportLatency(new LatencyReport(List.of(new ServiceLatencyReport(
            "inventory", // Exact latency-tracker name.
            durationMs,  // Measured service latency in milliseconds.
            10_000,      // Tracker sample lifetime in milliseconds.
            100,         // Maximum samples considered by this tracker.
            5            // Minimum-sample threshold for this tracker.
        ))));
    }
}
```

The caller handles a reporting failure separately from the outcome of the work
already performed. Spring owns this shared client's lifetime; do not close it
after each call.

### Request one token with one latency guard

“Get one checkout token only if inventory's expected latency is below 100 ms.”

```java
import com.ratelimitly.spring.method.MethodLatencyGuard;
import com.ratelimitly.spring.method.RateLimited;
import org.springframework.stereotype.Service;

@Service
public class GuardedCheckoutService {
    @RateLimited(
        context = "checkout", // Same bucket suffix as the unguarded example.
        rate = 100,           // Same rate-counter definition.
        window = "1s",        // Same rate-counter window.
        tokensRequested = 1,  // One token, consumed only if granted.
        guards = @MethodLatencyGuard(
            service = "inventory", // Match the reporting tracker's name.
            thresholdMs = 100,     // Required upper bound on expected latency.
            ttlMs = 10_000,        // Match the reporting tracker definition.
            maxSamples = 100,      // Match the reporting tracker definition.
            minSampleThreshold = 5 // Match the reporting tracker definition.
        ),
        reportLatency = false // InventoryMeasurements reports actual inventory time.
    )
    public void checkout() {
        // Perform the protected work here, after admission.
    }
}
```

The resource and guard form one decision. Automatic method reporting is
disabled here because the whole checkout method's duration is not necessarily
the inventory call's duration. See [latency measurement](docs/usage.md#latency-measurement).

## Going further

- [Usage and limitations](docs/usage.md): HTTP versus method protection,
  custom policies, resource names, outcomes, and latency measurement.
- [Configuration reference](docs/configuration.md): defaults, integration
  selection, HA policy, and observability.
- [Java client API](https://github.com/ratelimitly-com/rl-java-client/blob/main/docs/api.md):
  programmatic resource requests, independent reports, and canonical IDs.
- [Sample application](docs/sample.md): run the MVC and method examples locally.
- [Contributing](CONTRIBUTING.md) and [security guidance](SECURITY.md).

This public repository contains the Spring integration, not the RateLimitly
server. Maven publication is a separate, manually approved step; follow the build
instructions above. See the [known limitations](docs/usage.md#known-limitations)
and [publication notes](docs/public-readiness-plan.md).

## License

[MIT](LICENSE).
