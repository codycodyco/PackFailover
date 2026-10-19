# Contributing

PackFailover is a small Paper/Java plugin. Changes should keep the primary resource-pack delivery path non-invasive and make failure recovery predictable.

## Development environment

- JDK 25; Gradle 9.8.0 wrapper (`./gradlew`)
- Paper 26.2 API; PacketEvents 2.14.0
- Run `./gradlew clean build` and include the results in your pull request.
- For changes to packet handling, Minecraft/Paper behavior, or RPEngine integration, report the versions and describe any real-client checks separately from JUnit tests.

## Compatibility and safety

- Never cancel, rewrite, or globally replace another plugin's successful resource-pack push.
- A fallback must use **the same ZIP bytes and full SHA-1** as the request it recovers; do not infer a pack from truncated hashes.
- Only a matching `FAILED_DOWNLOAD` is eligible for automatic fallback. Respect user refusal and do not introduce unbounded retries.
- Keep directory scanning, hashing, and filesystem/HTTP I/O away from the Paper main thread; use the proper scheduler for player APIs.
- Treat CONFIGURATION status handling, mandatory packs, and multi-pack stacking as integration-sensitive.
- Do not add hard-coded public IPs, credentials, or sensitive logs. Do not modify unrelated services or server configuration.
- Do not claim a scenario is verified until the corresponding test evidence exists.

## Pull requests

Describe the problem, behavior change, compatibility impact, automated tests, and any manual test limitations. Prefer narrow PRs: documentation, runtime behavior, or protocol changes should be reviewable independently. Update [Architecture](docs/ARCHITECTURE.md), [Installation](docs/INSTALLATION.md), and [Test Plan](docs/TEST-PLAN.md) when relevant.

Experimental `/pf poc` commands are disabled by default and require `poc.enabled: true` plus `packfailover.admin`; do not enable them for production players.

Historical development notes are retained in [docs/development](docs/development/README.md). Keep current instructions in the top-level documentation.
