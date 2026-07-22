# Repository workflow

- Work directly on `main`.
- Do not create feature, release, or agent branches for this repository.
- Before pushing, fetch `origin/main`, integrate remote changes without force-pushing, and run the relevant verification.
- Push completed work to `origin/main` and keep no temporary local or remote branches.
- Build test APKs locally as the primary fast path and publish the local artifacts in a separate GitHub prerelease with checksums and build metadata.
- Keep GitHub Actions enabled as an independent remote verification and build path, but do not block local publication or wait for the slower remote build to finish.
