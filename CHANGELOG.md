# Changelog

## 2.0.0 (release candidate; not yet published)

- Prepare the first Maven registry publication of the parent POM, autoconfigure
  library, and starter through RateLimitly's public GitLab registry.
- Resolve Java client `3.0.0` from that registry instead of installing a pinned
  source checkout in normal CI. Java must be published before this change merges.
- Preserve the existing Spring integration behavior, Java 21 baseline, and MIT
  license. Source code and release downloads remain on GitHub.
- Add manual publication, exact-main CI/security gates, signed artifact
  verification, anonymous consumer checks, and interrupted-upload recovery.
- Test the sample application but exclude it from publication.
