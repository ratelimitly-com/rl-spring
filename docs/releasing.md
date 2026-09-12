# Public Maven publication

Source code, reviews, and CI stay on GitHub. The public GitLab
[registry project](https://gitlab.com/ratelimitly-com/maven-registry) hosts our Maven
artifacts without requiring consumer credentials:

```text
https://gitlab.com/api/v4/projects/86375734/packages/maven
```

## Sequence and exact artifacts

1. Publish and anonymously verify Java client `3.0.0` first.
2. Merge the Spring change using that published dependency and this repository
   URL. Require successful exact-main CI, source secret scan, and CodeQL.
3. Publish Spring `2.0.0` with the manual, approval-gated workflow.
4. Verify anonymous resolution with a clean standalone consumer before making
   the GitHub release public.

| Coordinate (`com.ratelimitly`, same Spring version) | Unsigned artifacts |
| --- | --- |
| `ratelimitly-spring-boot-parent` | POM |
| `ratelimitly-spring-boot-autoconfigure` | POM, library/source/Javadoc JARs |
| `ratelimitly-spring-boot-starter` | POM, starter/source/Javadoc JARs |

Exactly nine unsigned artifacts are signed and verified. The parent is required
for downstream resolution. The dependency-only starter's source/Javadoc archives
explain its role and include the MIT license. The sample remains tested in the
reactor but has `maven.deploy.skip=true`, skips signing, and is rejected by the
release allow-list. No server artifacts are part of this repository or release.

## Credential-free validation

```sh
python3 scripts/test_maven_bundle.py
python3 scripts/test_publication_policy.py
python3 scripts/test_registry.py
python3 scripts/test_reviewed_artifacts.py
SOURCE_DATE_EPOCH=$(git show -s --format=%ct HEAD) bash scripts/build-maven-dry-run.sh
```

Add new files to Git's index before the dry run: it copies tracked working-tree
files to a disposable directory. It uses an ephemeral GPG key and an empty Maven
settings file, then executes two real Maven deployments to a loopback-only HTTP
fixture. The fixture rejects duplicate artifact uploads. Both full test runs,
all signatures, MIT notices, POM coordinates, sample exclusion, and unsigned
byte reproducibility must pass. The second deployment also exercises the
reviewed-artifact gate. A final negative test changes the reviewed starter POM
and requires Maven to refuse the whole deployment before any module is uploaded.
The synthetic bundle is
`target/maven-dry-run-bundle.zip`; it is not a release.

## Protected publication

Use environment `maven-publication`, restricted to branch main, requiring
maintainer approval (self-approval allowed), with administrator bypass disabled.

Secrets: `GITLAB_MAVEN_USERNAME`, `GITLAB_MAVEN_TOKEN`,
`MAVEN_GPG_PRIVATE_KEY`, `MAVEN_GPG_PASSPHRASE`.
Variables: pinned full `MAVEN_GPG_FINGERPRINT` and
`MAVEN_PUBLICATION_APPROVED=true` only after readiness and signing-key recovery
are confirmed. The project-scoped Spring deploy token has package read/write
access only and expires on 2027-09-12. It can access all coordinates in the
dedicated project; it is not coordinate-scoped, and package-write also permits
deletion. Rotate it independently of the Java token.

Dispatch `publish-mvn` from main with `action=publish`, the full
`expected_commit`, and matching numeric `expected_version`. Before any upload,
existing package records (including incomplete ones) block publication. Maven
builds and signs all modules, compares each POM/JAR against the reviewed dry-run
artifacts, and uses `deployAtEnd` so a later reactor validation failure cannot
trigger an early module upload. The actual deploy-plugin path is exercised
against the duplicate-rejecting fixture; no Sonatype plugin is used.

Uploads become public immediately: GitLab has no atomic multi-module staging.
Duplicate Maven uploads are disabled. Never replace or delete a published version
to repair it. After an interruption, inspect the registry and logs. If all files
arrived, `action=finalize` with the same SHA/version verifies them without
uploading and finishes the GitHub release. Missing files require an explicitly
reviewed recovery or a new version. Automatic publish reruns are refused.
Keep main at the release commit until finalization completes.

Verification downloads anonymously, compares unsigned bytes, checks signatures
against the pinned public key, and runs the standalone consumer with an empty
Maven cache and settings. GitHub assets contain the POMs/JARs, signatures, public
key, and SHA256SUMS. Production keys/caches are not exposed to PR jobs; the
publishing job does not restore a Maven cache and removes its GPG home afterward.

## Distribution decision

Sonatype classified RateLimitly's service-client publishing as commercial and
quoted an annual subscription on 2026-09-11. We selected GitLab's Free registry;
there is no Sonatype publication or paid subscription step. The MIT license and
`com.ratelimitly` coordinates remain unchanged. Third-party dependencies still
use Maven Central, not a GitLab mirror.

References: [GitLab Maven registry](https://docs.gitlab.com/user/packages/maven_repository/),
[project deploy tokens](https://docs.gitlab.com/user/project/deploy_tokens/).
