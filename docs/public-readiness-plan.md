# Public source and package publication

The source repository is public. Maven Central publication remains separate;
development uses the explicitly documented source-installed snapshots.
Current tracking: [launch verification](https://github.com/ratelimitly-com/rl-spring/issues/1),
[reporting design debt](https://github.com/ratelimitly-com/rl-spring/issues/2), and
[Maven publication](https://github.com/ratelimitly-com/rl-spring/issues/3).
The initial preparation was tracked in the
[private archive](https://github.com/ratelimitly-com/rl-spring-private-archive/issues/9)
(maintainer access required). The historical plan below records its scope and sequence.
The final [repository-preparation checklist](repository-preparation.md) records
snapshot inspection, security checks, solo-maintainer settings, and transition gates.

Preparation was performed privately. A green compatibility
build alone does not complete the broader behavior/security audit. Source visibility
and Maven Central publication are separate decisions, and neither publishes
the RateLimitly server.

## Baseline

[PR #8](https://github.com/ratelimitly-com/rl-spring-private-archive/pull/8) migrated the private
integration to public Java client `3.0.0-SNAPSHOT`. The merged baseline
`1088cbb` passed Linux JDK 21/25, macOS JDK 21, and Windows JDK 21 with 35
Java-client tests and 35 Spring tests. Spring is `2.0.0-SNAPSHOT` and builds
the pinned public dependency from source. Maven Central publication remains
tracked by [Java client #9](https://github.com/ratelimitly-com/rl-java-client/issues/9).

## Ordered deliverables

1. **License and client documentation.** Add MIT source/POM licensing,
   layered README examples, usage and configuration references, contribution
   and security guidance. Verify the examples, links, metadata, complete tests,
   and PR CI. Label historical designs as proposals, not implemented contracts.
2. **Behavior/security audit and fixes.** Review automatic configuration,
   custom beans, inert WebFlux options, observation disablement, filter versus
   interceptor behavior, async/error redispatch, nested admission, resource
   accounting, latency measurement, failure handling, expression trust,
   cardinality, error disclosure, and client/executor ownership. Add regression
   tests for confirmed findings. Agree on any deferred limitations explicitly.
3. **Repository tooling and housekeeping.** Reconcile the older test issue #3
   and CI PR #1. Add security/dependency checks and contributor guidance for
   issues/PRs. Require CI while supporting a solo maintainer without mandatory
   external approval. Correct repository metadata. Review the intended public
   snapshot for secrets, internal-only material, attribution, and notices.
4. **Artifact usability.** After Java client `3.0.0` is published, replace the
   source-installed snapshot dependency and validate a clean external Maven
   consumer. Before Spring publication, verify POM metadata, sources/Javadoc,
   license inclusion, signing, reproducibility, and publication gates. Publish
   only integration modules and their required parent, not the sample app.
   Source can open first if installation instructions remain accurate.
5. **Public transition.** Present the completed checklist and accepted limits
   for explicit approval. Archive the private repository and create a clean
   public `rl-spring` snapshot, as done for the other clients. Verify attribution,
   license, public links, anonymous build/test, settings, and exact public-main
   CI/security checks. Preserve private history; do not expose historical refs
   accidentally or rewrite them as incidental cleanup.

Use focused PRs. The documentation foundation does not resolve runtime audit
findings or authorize a visibility change. Do not close the umbrella issue
until the agreed public-source checks have passed. Unfinished Maven publication
can remain in its own issue rather than holding source visibility indefinitely.

## Validation boundaries

Contributor tests use local synthetic fixtures without private credentials or
server code. Live sample scripts are optional and do not replace those tests.
Exact post-merge CI is required before declaring any step complete. Do not
skip failing tests or weaken assertions to obtain a green release.

The Java dependency previously showed a macOS timing-test failure in
`promptOldestServerResponseWins`; later checks passed. Retain this as an
upstream reliability follow-up unless it is diagnosed and resolved. Passing
once does not establish that the timing sensitivity has disappeared.
