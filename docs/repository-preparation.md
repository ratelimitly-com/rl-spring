# Repository preparation and public transition

> Historical preparation record. Current dependency, registry, and publication
> instructions are in [the release runbook](releasing.md) and the README.

This historical maintainer runbook records preparation of the initial public
source snapshot from private main `fa323b8`, after review and merge of PR #16.
Its original [tracking issue](https://github.com/ratelimitly-com/rl-spring-private-archive/issues/9)
is retained in the private archive (maintainer access required). References to
private settings below describe preparation, not the new public repository.
Public source availability and Maven Central publication are separate milestones.

## Source snapshot review

The review started from main `926ef4c` (94 tracked files), after the behavior
audit and 167 passing tests. The intended public source is a fresh snapshot,
not a copy of private branches, tags, pull-request refs, releases, or history.

Checks performed on that tree:

- MIT source license and matching POM license declaration are present.
- No tracked compiled archives, private-key files, submodules, vendored
  dependency sources, or notices indicating copied third-party source were found.
  Dependencies remain external Maven artifacts with their own licenses. This
  source inspection does not replace release-artifact license checks.
- Application guides, sample configuration, and scripts were inspected for
  embedded credentials, private filesystem paths, and server implementation
  details. Examples use environment injection or explicit synthetic fixtures.
- Gitleaks' standard rules initially flagged six copies of the same public
  synthetic NONE key. The allowlist matches only that exact credential string;
  it does not ignore test directories, generic key families, or other findings.
- Additional rules reject encoded AES/cookie request credentials and root
  secrets. Detector tests check those rules and the standard GitHub-token rule.

Repeat the scan on the final committed PR head and exact public snapshot.
No automated scan proves the absence of secrets. Previously accepted rotated
values in private historical refs do not require a history rewrite for this
clean-snapshot transition. Preserve that history privately.

The initial public commit must use
`wojciech-fraczak <wojciech@ratelimitly.com>` as both author and committer.
Verify the new GitHub attribution; do not infer it from local Git settings alone.

## Security and maintenance checks

The [security workflow](../.github/workflows/security.yml) runs a redacted,
checksum-pinned Gitleaks scan of `git archive HEAD`. It does not read a
developer's untracked files, build outputs, credentials, or sibling repositories.
Use Gitleaks 8.30.1 locally:

```sh
bash scripts/test-secret-scan.sh
bash scripts/scan-secrets.sh
```

Set `GITLEAKS_BIN` to an installed binary path if it is not on `PATH`. Verify the
upstream release checksum before executing downloaded tools. No scanner account,
private token, or Gitleaks Action license is required. No secret findings are
uploaded as artifacts, and output is redacted.

Dependabot checks Maven projects (including the independent consumer) and
GitHub Actions weekly. Dependabot alerts and automated security updates are
enabled separately in repository settings. Review updates; do not auto-merge.

CodeQL Java/Actions and dependency review are configured for public runs.
Code Security is disabled on the private repository, so those jobs are
explicitly skipped here, not claimed as passed. The organization reports an
Enterprise plan; this check does not establish whether unused security seats
are licensed, and this work does not activate paid add-ons. GitHub documents availability
for [CodeQL](https://docs.github.com/en/code-security/reference/code-scanning/troubleshoot-analysis-errors/private-repository-enablement)
and [dependency review](https://docs.github.com/en/code-security/concepts/supply-chain-security/dependency-review).
Java analysis uses a manual build with the same pinned public dependency as CI.
Do not enable a second default-setup CodeQL configuration alongside this workflow.

PR workflows use `pull_request`, never `pull_request_target`, and do not need
repository secrets. Checkout credentials are not persisted. CodeQL's job has
only the analysis upload permissions in addition to read access; fork tokens
remain subject to GitHub's restrictions. No PR workflow can publish a package.
An actual external-fork PR remains a public-transition smoke test; private
preparation cannot demonstrate an anonymous fork's full execution path.

## Solo-maintainer branch policy

The [baseline ruleset](repository-settings/main-quality-gates.json) requires a
PR, resolved review conversations, current-base CI, the four platform jobs,
hygiene, and the source secret scan. It blocks force-pushes and deletion, with
no bypass actors. It requires **zero external approvals** and no last-pusher
approval; one maintainer can review and merge when checks pass.

The private repository's baseline is active as ruleset `22593360` (verified
during preparation). Workflow tokens default to read-only and cannot approve
PRs. Dependabot alerts/security updates are enabled, metadata is corrected,
stale CI PR #1 is closed as superseded, and coverage issue #3 is resolved.
These observations must be rechecked on the new repository after transition.

The new source-scan check must pass on the preparation PR before it can merge.
For the public repository, apply the additional
[public security ruleset](repository-settings/public-code-security.json) after
validating `Analyze (java-kotlin)`, `Analyze (actions)`, and `dependency review`.
It also blocks high/critical CodeQL security alerts and error-level results.
A successful analysis job alone does not mean
there are no findings. Inspect alerts, and do not waive failures to publish.
Settings do not transfer automatically when creating the fresh repository.

## Retained limitations

Automatic reports still measure the whole admitted operation and are coupled
to guards. They are not per-dependency measurements or delivery acknowledgements.
Independent reports remain available through the Java client. The design work
stays in [#2](https://github.com/ratelimitly-com/rl-spring/issues/2), rather than
blocking source publication on an unrequested reporting API redesign.

An earlier upstream Java timing-test failure remains noted in the
[readiness plan](public-readiness-plan.md#validation-boundaries). Later green
runs are evidence for those commits, not proof that timing sensitivity is fixed.
Current failures would still block the transition; no tests are skipped for it.

## Transition runbook (after PR review)

1. Verify the exact merged preparation commit passes CI and secret scanning.
   Record the source tree ID and review the retained limitations above.
2. Inventory/export repository settings and tracking links. Preserve the private
   repository as `rl-spring-private-archive`; do not expose its historical refs.
3. Create a fresh `rl-spring` with one reviewed snapshot commit. Keep all POMs
   on their current snapshot versions; do not copy release tags or assets.
4. Update documentation's preparation-status wording and historical tracking
   links to the archive where appropriate. Keep normal Java-client links public.
5. Verify identity, MIT license, source contents, description, topics, issue/PR
   guidance, and explicit source-install instructions. Do not claim Central
   availability. Enable the dependency graph, alerts, security updates, secret
   scanning and push protection available for the public repository.
6. Run exact public-main CI, source scanning, and CodeQL; inspect alerts. Apply
   the baseline and public security rules without external approval requirements.
7. Verify an anonymous checkout/build/consumer test and an external-fork PR,
   including dependency review, without private credentials. Confirm no package,
   sample release, private source, historical ref, or unintended author leaked.
8. Record evidence and close the public-source readiness milestone. Keep Maven
   Central and reporting design work in their separate issues.

Archiving/creating repositories is not performed by any workflow in this PR.
