# Maven Central preparation and release contract

This repository is not yet publishing Maven artifacts. It remains at
`2.0.0-SNAPSHOT`, depending on the documented public Java-client snapshot.
Track completion in [publication issue #3](https://github.com/ratelimitly-com/rl-spring/issues/3).

## Exact deployment set

All coordinates use `com.ratelimitly` and the same Spring release version:

| Artifact | Contents |
| --- | --- |
| `ratelimitly-spring-boot-parent` | Parent POM and signature |
| `ratelimitly-spring-boot-autoconfigure` | POM, library/source/Javadoc JARs, signatures |
| `ratelimitly-spring-boot-starter` | POM, starter/source/Javadoc JARs, signatures |

The starter is dependency-only. Its source and documentation archives explain
that fact and point to the autoconfigure API; no artificial Java class is added.
All deployable JARs carry the MIT license. The parent must be published because
the child POMs reference it. Do not flatten away that relationship accidentally.

The sample stays in the reactor and test suite but is excluded from Central
staging. Both Central's artifact exclusion and the sample's deployment skip are
explicit. The verifier checks the exact allow-list, hashes, signatures, metadata,
and JAR contents. Neither the sample nor any RateLimitly server is published.

## Preparation checks

The `publish-mvn` workflow is currently a credential-free packaging dry run on
PRs, main pushes, and manual dispatch. It cannot publish a GitHub release or
upload to Central. It builds the full reactor with a disposable signing key,
generates the actual Central-plugin bundle against a loopback fixture, verifies its
contents, and compares unsigned release artifacts across clean builds.
Because the plugin handles snapshots differently, this runs in a temporary copy
of tracked working-tree files with a synthetic `-dry-run` version. The original
checkout/POM versions do not change. Add newly created files to Git before running
the dry run. Its final bundle is `target/central-dry-run-bundle.zip`.
Plugin 0.11.0 skips staging entirely with `skipPublishing=true`, contrary to its
description of bundle-only mode. The test therefore uses nonfunctional credentials
and an ephemeral loopback HTTP fixture that accepts upload/validation requests
but cannot publish anything. No real Portal credentials or remote publishing
endpoint are used. The normal POM still defaults to `central.skipPublishing=true`.

Install the pinned public Java dependency as documented in CONTRIBUTING first.
Then run:

```sh
python3 scripts/test_central_bundle.py
bash scripts/build-central-dry-run.sh
```

These are development checks, not a claim that a snapshot bundle is eligible for
Central release validation. Do not use production credentials for them.

## Rollout after Java publication

1. Complete the [Java release](https://github.com/ratelimitly-com/rl-java-client/issues/9)
   and verify Java `3.0.0` from Central with an empty Maven cache.
2. Replace the Java snapshot dependency and source-install steps in all Spring
   workflows/docs with the published coordinate. Run all tests and a Central-only
   external consumer, including auto-configuration discovery and shutdown.
3. Extend this workflow with the Java client's reviewed manual stage/finalize
   approach. Never publish on a numeric-version push. Require the exact reviewed
   main SHA, CI/security checks, protected-environment approval, and Sonatype
   namespace/policy/signing prerequisites. Store production credentials only in
   a main-restricted `maven-central` environment. Clarify Sonatype's current
   [service-dependent SDK terms](https://central.sonatype.org/publish/producer-terms/)
   before publishing; do not infer free eligibility from the MIT license alone.
4. In a separate release PR, select the intended Spring version (planned `2.0.0`),
   update every parent reference and consumer version, and reject all snapshot
   runtime dependencies before staging. Use the release tag for SCM metadata.
5. Stage one bundle containing the parent and two libraries, with
   `autoPublish=false` and `waitUntil=validated`. Record deployment ID and hashes;
   inspect then publish that existing deployment in Portal.
6. Verify public POMs, all JARs, signatures, parent resolution, and a clean
   Central-only starter consumer before creating the matching GitHub release.
   A Portal upload is not a public release; a public 404 is not proof that no
   deployment is in progress. Resume by deployment ID; never overwrite a version.

Publishing credentials and upload/finalize jobs are intentionally absent from
this preparation PR. Maven artifacts, GitHub releases, and source visibility are
separate decisions. Accepted reporting design debt remains separate in #2.
