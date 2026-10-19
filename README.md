# PackFailover

PackFailover is a Paper plugin that gives Java Edition players a second chance when a server resource-pack **download fails**. It keeps the original resource-pack request intact. Only an eligible player who receives `FAILED_DOWNLOAD` gets a retry from an operator-hosted HTTP endpoint.

The fallback serves a locally verified, **byte-identical ZIP** using the original pack UUID and full SHA-1. It does not proxy the original URL, generate resource packs, or replace RPEngine.

## Compatibility and status

| Component | Target / tested version |
| --- | --- |
| Paper | 26.2 (API `26.2.build.134-stable`) |
| Java | 25 |
| PacketEvents | 2.14.0 (installed as a plugin) |
| RPEngine | 0.2.20 in the recorded integration test |
| Build | Gradle 9.8.0 wrapper |

**Verified:** On a real Java client, the original RPEngine PLAY-phase pack failed to download and PackFailover retried the same pack UUID and SHA-1 from its local HTTP endpoint; the pack then downloaded and loaded successfully. See the [historical test report](docs/development/PROTOCOL-POC.md) (Section 15.11; the report retains earlier snapshots).

**Not yet fully verified:** real-client CONFIGURATION-phase recovery, `required=true` pack behavior, interaction with upstream RPEngine status/UI after a successful fallback, and an isolated main-pack failure with the other stacked packs unaffected. In the strict test, RPEngine still logged a download failure even though PackFailover recovered the client. Do not assume upstream state is synchronized.

This is an early release; test with your exact server and resource-pack setup before deploying broadly.

## How it works

1. PacketEvents observes outgoing Java resource-pack pushes (PLAY or CONFIGURATION) without altering them.
2. PackFailover records the pack UUID, complete SHA-1, source hostname, and player session.
3. Configured provider directories are scanned asynchronously. Valid ZIP files are hashed and copied to an immutable, SHA-1-addressed cache.
4. On an eligible `FAILED_DOWNLOAD`, the plugin sends that player **one** fallback request using the original UUID and SHA-1 with a different URL.
5. The built-in HTTP server serves the cached ZIP at `/packs/<sha1>.zip`. Normal downloads are unaffected.

Fallback is skipped if the pack is not from an allowed hostname, its complete hash or verified ZIP is unavailable, or the HTTP endpoint is not ready. `DECLINED` and `FAILED_RELOAD` do not trigger automatic fallback.

## Build

```bash
./gradlew clean build
```

Requires JDK 25 and access to Maven Central, PaperMC, and CodeMC repositories. The jar is generated at `build/libs/PackFailover-0.1.0-SNAPSHOT.jar`.

## Quick start

1. Install Paper 26.2 on Java 25 and PacketEvents 2.14.0.
2. Copy the PackFailover jar into `plugins/`, then start the server to generate `plugins/PackFailover/config.yml`.
3. Set `tracking.primary-host-allowlist` to the original pack server's exact hostname.
4. Point `providers.directories` at the directory containing the **actual** source ZIP files. For RPEngine 0.2.20, the tested output directory was `plugins/RPEngine/output`.
5. Set `http.public-url` to the **player-reachable** HTTP base URL and forward the configured HTTP port (default 8181/TCP) if necessary.
6. Run `/pf index` and `/pf status`; verify a matching ZIP is indexed and the endpoint is reachable from outside the server.
7. Enable `fallback.enabled` once the endpoint and artifact are verified.

RPEngine can keep `host.mode: upload`. Its successful downloads still use the original provider.

```yaml
tracking:
  primary-host-allowlist:
    - "packs.resourcepack.ai"

providers:
  directories:
    - id: rpengine
      enabled: true
      path: "plugins/RPEngine/output"
      glob: "**/*.zip"
      max-depth: 3

fallback:
  enabled: true
  dry-run: false
  max-attempts-per-pack: 1
  # These paths need additional real-client validation:
  config-phase: false
  allow-required: false

http:
  enabled: true
  bind-address: "0.0.0.0"
  port: 8181
  public-url: "http://packs.example.com:8181"
```

This is an **illustrative override**, not a replacement for the complete generated config. The shipped config currently enables `config-phase` and `allow-required` by default; setting both to `false` is a conservative recommendation until you test those cases. Never use a loopback URL as `http.public-url`.

See [Installation](docs/INSTALLATION.md) for Docker/FRP examples, verification, and rollback.

## Administration

| Command | Purpose |
| --- | --- |
| `/pf status` | Show capture, HTTP, index, and fallback status |
| `/pf index` | Rescan provider ZIPs asynchronously |
| `/pf cache` | Inspect cached artifacts |
| `/pf debug <player>` | Inspect captured pack attempts |
| `/pf artifact <zip>` | Verify and cache a specific ZIP |
| `/pf reload` | Reload settings (HTTP/provider changes may need restart) |

The `/pf poc` commands are experimental diagnostics that can deliberately send an unreachable pack URL to a player. They are currently available to `packfailover.admin` and **must not be used on production players**; restricting them behind a separate development-mode switch is planned.

## Documentation

- [Installation and rollback](docs/INSTALLATION.md)
- [Architecture and protocol boundaries](docs/ARCHITECTURE.md)
- [Test plan and validation status](docs/TEST-PLAN.md)
- [Troubleshooting](docs/TROUBLESHOOTING.md)
- [Contribution guidelines](CONTRIBUTING.md)
- [Historical experiment log](docs/development/README.md)

**Scope:** Java Edition packs only. No Bedrock `.mcpack` fallback, arbitrary URL proxying, or guaranteed recovery for mandatory packs.
