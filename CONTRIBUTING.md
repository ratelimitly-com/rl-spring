# Contributing

Contributions are welcome under the [MIT License](LICENSE). Keep changes
focused, document application-visible behavior, and add regression tests for
behavioral fixes. The [publication notes](docs/public-readiness-plan.md) distinguish
the public source release from pending Maven registry publication.

## Build and test

Use a JDK (not just a JRE) version 21 or newer and Maven. Run these commands
from the `rl-spring` repository root. The POM resolves Java client `3.0.0`
anonymously from the public GitLab Maven registry. **The Java release must be
published before this change can merge or a clean CI build can pass.** No
source-checkout fallback is used in CI.

```sh
mvn -B -ntp clean install
mvn -B -ntp -f integration-tests/consumer/pom.xml clean verify
```

`clean install` runs the tests and installs the parent, autoconfigure, starter,
and sample artifacts into your local Maven repository. It does not publish to
a remote registry. Use `clean verify` for subsequent checks when local
installation is not needed. Do not use `-DskipTests` as a validation gate.

The separate consumer has no reactor parent or module membership. It uses the
installed starter/autoconfigure jars and Boot's auto-configuration discovery to
test non-web method admission, denial, null arguments, client-only mode, and
shutdown. Reinstall the reactor before this check after changing library code.
This checks locally built Spring artifacts; publication separately verifies the
anonymous registry with an empty Maven cache.

The suite uses synthetic credentials, a local UDP responder, Spring test
contexts, and an embedded Tomcat server on a random local port. Local sockets
must be permitted. It requires neither a live API key nor production DNS or
a private RateLimitly server checkout. README Java examples are compiled and checked by
the documentation tests. GitHub CI runs the Spring suite against the published Java dependency
on Linux JDK 21/25, macOS JDK 21, and Windows JDK 21.

## Scope and documentation

Keep this repository focused on the Spring integration. The public
[Java client](https://github.com/ratelimitly-com/rl-java-client) owns its client
API and delivery behavior; link to its documentation instead of duplicating
the protocol or adding another HA implementation here.

Application documentation must work without private paths, server source,
or internal specifications. Use “API key,” not “tenant,” when referring to
the credential or identity it represents. Describe implemented behavior
separately from proposals and known limitations. Avoid credentials, captured
authenticated traffic, personal request data, and unbounded identifiers in
examples, fixtures, issue reports, and logs.

## Pull requests

- Link the relevant issue and state which acceptance items the PR addresses.
- Add a failing regression test first when fixing observable behavior.
- Run the complete Maven checks and `git diff --check` before requesting review.
- For workflow changes, also run `actionlint` when available.
- Run `bash scripts/test-secret-scan.sh` and `bash scripts/scan-secrets.sh`
  with Gitleaks 8.30.1 before a public-snapshot handoff; see
  [repository preparation](docs/repository-preparation.md#security-and-maintenance-checks).
- Keep development versions as snapshots. A numeric version change is a
  release decision, not routine cleanup.
- Do not change repository visibility, rewrite history, configure credentials,
  or publish packages as incidental work.

The `publish-mvn` workflow performs credential-free dry runs on PRs and pushes:
two signed Maven deployments to a loopback fixture, exact module allow-list
validation, and reproducibility. Manual `publish`/`finalize` actions are
main-only and approval-gated. See [the publication runbook](docs/releasing.md).
Neither a numeric version change nor a push publishes anything.
Never publish the sample app or server as a Maven library.

PR checks, including those from public forks, need no private credentials.
Contributors do not need a production API key or access to a private repository
to run the documented checks. Dependabot updates and security findings are
reviewed changes, not permission to publish or auto-merge dependencies.

For vulnerabilities, follow [SECURITY.md](SECURITY.md), not a public issue.
