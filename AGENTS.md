# PackFailover — Codex project instructions

## Project purpose
Develop a small, non-invasive Paper plugin that retries a player's failed Java resource-pack download using a verified, identical ZIP served from the operator's fallback HTTP endpoint. Normal RPEngine `upload` behavior must remain untouched for successful players.

## Fixed target and toolchain
- Target server: **Paper 26.2** (not 1.21.x); Java toolchain and compile target: **Java 25**.
- Prefer Gradle 9.8 with Kotlin DSL. Verify the actual Paper API artifact/version and PacketEvents 2.x compatibility from current official sources; do not invent Gradle coordinates, API methods, packet layouts, or version compatibility.
- Runtime environment: Ubuntu, Paper in Docker Compose, FRP TCP forwarding. An **8181/TCP** fallback route has been provisioned; do not reconfigure Docker/FRP or modify the production server without explicit approval.
- Existing related plugins: RPEngine (`host.mode: upload`), PacketEvents, Geyser-Spigot, Floodgate-Spigot.

## Required reading
- Read `docs/ARCHITECTURE.md` **in full** before making design or code changes.
- Treat it as the architecture baseline. Keep design decisions, compatibility findings, and test outcomes in `docs/PROTOCOL-POC.md`.

## Development constraints
1. **Stage 0 PoC first.** Do not implement all providers, hosting, and production fallbacks before validating the protocol behavior.
2. Observe outgoing resource-pack packets (configuration and play phases) and correlate with resource-pack result events using player UUID + pack UUID + session generation.
3. Only retry `FAILED_DOWNLOAD` for approved sources; never retry `DECLINED` or indiscriminately remove/clear other resource packs.
4. Before fallback, verify the exact ZIP's **full SHA-1** matches the original request. If no verified artifact is available, safely skip fallback.
5. First test same resource-pack UUID + same ZIP/SHA-1 + different URL. Whether this works is **unverified**, not an assumption. Assess behavior with RPEngine and resource-pack stacking using a real Java client.
6. Perform Paper player API calls in the valid server scheduler/thread context. Never hash files, scan directories, or perform HTTP I/O on the game main thread.
7. No Bedrock `.mcpack` fallback in V0.1. Handle Floodgate/Geyser players without trying to send Java resource packs.
8. No arbitrary URL proxy, unbounded retries, or arbitrary filesystem download endpoints. Do not ship secrets or hard-coded public IPs.
9. Keep all changes buildable, testable, minimal, and reversible. Run `./gradlew build` if tooling and dependencies permit; explicitly report any environmental blocker.
10. Never assert successful client-side testing without observed Minecraft client evidence. Stop for user-provided PoC results before declaring Stage 0 passed.

## First task
Implement **only Stage 0 PoC** as defined in `docs/ARCHITECTURE.md` and report:
- Resolved versions and verified API surface
- Files changed and Gradle build result
- How to simulate `FAILED_DOWNLOAD` safely in a test environment
- How to verify fallback using the same pack UUID and a verified identical ZIP
- Whether upstream RPEngine state and pack stacking remain correct
- Remaining unknowns and the exact human verification steps

## Production safety
Default to test-server work. Never overwrite `plugins/RPEngine`, live worlds, credentials, FRP settings, or the production Compose file. If a change requires production downtime or privileged access, propose a reviewable plan and ask first.
