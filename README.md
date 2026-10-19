# PackFailover

Small, non-invasive Paper plugin that retries a **failed Java resource-pack download** using a
verified, byte-identical ZIP served from the operator's fallback endpoint. Normal RPEngine
`host.mode: upload` behavior for successful players remains untouched.

> **Current state: Stage 1 MVP.**
> Stage 0 protocol PoC passed with a real Java client (same pack UUID + SHA-1, different URL).
> Stage 1 implements automatic capture, full-SHA-1 indexing, immutable cache, HTTP fallback,
> and one automatic retry per pack in PLAY and CONFIGURATION.
>
> Real RPEngine output-path verification is still required on the actual RPEngine server;
> see [docs/PROTOCOL-POC.md](docs/PROTOCOL-POC.md) and [docs/INSTALLATION.md](docs/INSTALLATION.md).

## Target toolchain

| Component | Version |
|---|---|
| Paper API | `io.papermc.paper:paper-api:26.2.build.134-stable` |
| Java | 25 |
| Gradle | 9.8.0 (Kotlin DSL) |
| PacketEvents | `com.github.retrooper:packetevents-spigot:2.14.0` |
| JUnit | 6.1.3 |

## Build

```bash
./gradlew clean build
```

The plugin jar is written to `build/libs/PackFailover-0.1.0-SNAPSHOT.jar`.

Build requirements:

- JDK 25 (`JAVA_HOME` or a Gradle toolchain installation).
- Network access to Maven Central, `repo.papermc.io`, and `repo.codemc.io`.

> Sandbox note: if the build environment blocks writes to `~/.gradle`, use a workspace-local
> Gradle home: `GRADLE_USER_HOME=$PWD/.gradle-home ./gradlew build`.

## Install (test server first)

1. Paper 26.2 server on Java 25.
2. Install PacketEvents 2.14.0 (the release bundle, not the thin compile artifact).
3. Drop `PackFailover-0.1.0-SNAPSHOT.jar` into `plugins/`.
4. Configure:
   - `tracking.primary-host-allowlist` (for example `packs.resourcepack.ai`);
   - `providers.directories` (verify the real RPEngine output path);
   - `http.public-url` (client-reachable base URL);
   - `fallback.enabled: true` when ready.
5. Start the server and check:

   ```text
   [PackFailover] packetevents_hooked version=2.14.0
   [PackFailover] http_started bind=... publicUrl=...
   [PackFailover] index_done ... indexedSha1=...
   ```

6. Run `/pf status` and `/pf index`.

## How it works

```text
RPEngine / other plugin
  │  ResourcePackPush (CONFIGURATION or PLAY)
  ▼
PacketEvents passive capture
  │  player UUID + pack UUID + URL + full SHA-1 + required + prompt
  ▼
session store (connection epoch + generation)
  │
  ├─ client status: PLAY  -> PlayerResourcePackStatusEvent
  │                 CONFIG-> PacketEvents config status packet
  ▼
FAILED_DOWNLOAD
  │
  ├─ full SHA-1 -> registry (indexed from providers)
  ├─ immutable cache -> HTTP GET/HEAD /packs/<sha1>.zip
  └─ one fallback push: same UUID + same SHA-1 + fallback URL
       PLAY  -> Player#addResourcePack (main thread)
       CONFIG-> PacketEvents WrapperConfigServerResourcePackSend
```

The original packet is never modified, cancelled, or removed. `setResourcePack` is deliberately
not used because Paper 26.2 clears all resource packs before pushing.

## Key commands

```text
/pf status
/pf index
/pf cache
/pf debug <player>
/pf reload
/pf artifact <zip>
/pf poc simulate <player>
/pf poc send <player> <sha1> [required]
/pf poc resend <player> [pack-uuid]
/pf poc abort <player>
```

## Safety

- Only `FAILED_DOWNLOAD` triggers automatic fallback; `DECLINED`/`FAILED_RELOAD` never do.
- Maximum one retry per pack (`fallback.max-attempts-per-pack: 1`).
- Fallback requires a complete 40-hex SHA-1 and a locally verified/cached ZIP.
- Source allowlist matches exact hostnames; no substring matching.
- HTTP serves only `/packs/<40-lowercase-hex>.zip` from the immutable cache.
- No URL proxy, no arbitrary filesystem download endpoint, no upload.
- Bedrock/Floodgate players are not sent Java packs when Floodgate detection is available.
- Production FRP/Docker files are not modified by the plugin.

## Documentation

- [Architecture baseline](docs/ARCHITECTURE.md)
- [Stage 0/1 protocol and verification report](docs/PROTOCOL-POC.md)
- [Installation, Docker/FRP and rollback](docs/INSTALLATION.md)
- [Test plan](docs/TEST-PLAN.md)
- [Project instructions](AGENTS.md)
