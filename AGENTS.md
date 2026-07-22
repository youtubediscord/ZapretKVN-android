# Repository workflow

- Work directly on `main`.
- Do not create feature, release, or agent branches for this repository.
- Before pushing, fetch `origin/main`, integrate remote changes without force-pushing, and run the relevant verification.
- Push completed work to `origin/main` and keep no temporary local or remote branches.
- Build `arm64-v8a` and `armeabi-v7a` test APKs locally as the primary fast path and publish both local artifacts in a separate GitHub prerelease with checksums and build metadata.
- Keep GitHub Actions enabled as an independent remote verification and build path, but do not block local publication or wait for the slower remote build to finish.
- For Android device, VPN, TUN, DNS, crash, hang, or performance testing, use the project skill at `.agents/skills/test-zapret-android/SKILL.md`; collect ADB system evidence and the app diagnostic before changing the implementation.
