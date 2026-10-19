# Installation and rollback

PackFailover supplies a fallback HTTP URL to Java Edition clients when an eligible original resource-pack download fails. It does not replace the original RPEngine host or modify Docker/FRP settings on its own.

## Requirements

- Paper **26.2** running on **Java 25**
- PacketEvents **2.14.0**, installed as a server plugin
- PackFailover jar (build with `./gradlew clean build`)
- A player-reachable HTTP endpoint for verified fallback ZIPs (port 8181 by default)
- Read access to the actual resource-pack ZIP files produced by RPEngine or another provider

The recorded integration test used RPEngine 0.2.20 and `host.mode: upload`.

## Install

1. Copy `build/libs/PackFailover-0.1.0-SNAPSHOT.jar` into the Paper `plugins/` folder. Install PacketEvents separately.
2. Start Paper once so `plugins/PackFailover/config.yml` is generated.
3. Set `tracking.primary-host-allowlist` to the **exact original hostname** (no URL scheme, ports, or wildcard).
4. Configure `providers.directories` with a root that actually contains the source ZIPs.
5. Set `http.public-url` to a URL reachable by players, not the server's loopback address.
6. Restart after HTTP bind or provider changes, then check `/pf status` and `/pf index`.
7. Test the HTTP response and full SHA-1 from an external machine. Enable real fallback only when the source and endpoint are verified.

For RPEngine:

```bash
find plugins/RPEngine -maxdepth 4 -type f -name '*.zip'
```

In the recorded RPEngine 0.2.20 setup, the main bundle and rigs ZIPs were under `plugins/RPEngine/output`. Different RPEngine releases or configurations may use a different layout.

## Configuration example

Edit the generated YAML; do not discard unrelated defaults:

```yaml
enabled: true
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
  config-phase: false
  allow-required: false
http:
  enabled: true
  bind-address: "0.0.0.0"
  port: 8181
  public-url: "http://packs.example.com:8181"
cache:
  directory: "plugins/PackFailover/cache"
```

**Important:** Replace the example hostname with your actual public endpoint. `http.public-url` is a client-facing base URL; the plugin constructs `<public-url>/packs/<full-sha1>.zip`. The shipped `config.yml` defaults `fallback.config-phase` and `fallback.allow-required` to `true`; this example deliberately disables those **not-yet-real-client-verified** cases. Other production settings remain unchanged.

`/pf reload` refreshes configuration, but changing an HTTP listener or provider roots requires a restart for the runtime components to reflect the changes.

## Docker + FRP (example only)

If Paper runs in Docker Compose and FRPC runs on the Docker host, publish the fallback listener locally by **adding** the appropriate mapping to your existing `ports` list:

```yaml
services:
  paper:
    ports:
      - "127.0.0.1:8181:8181/tcp"
```

The listener within the container must bind to `0.0.0.0` to accept the forwarded connection. Add an FRP TCP proxy in the applicable test/production FRPC configuration after reviewing existing mappings:

```toml
[[proxies]]
name = "packfailover-http-8181"
type = "tcp"
localIP = "127.0.0.1"
localPort = 8181
remotePort = 8181
```

Ensure the FRPS host and network firewall permit the public port, and `http.public-url` matches the actual public address. If port 8181 is already in use on either host, choose different ports and update the URL and mappings consistently. Never expose an administrator-only service accidentally.

## Verify

From the Paper console:

```text
/pf status
/pf index
/pf cache
/pf debug <player>
```

Confirm the PacketEvents capture hook is available, HTTP reports ready, and indexing finds at least one expected SHA-1. From a machine **outside** the Minecraft host:

```bash
curl -I 'http://packs.example.com:8181/packs/<full-sha1>.zip'
curl -fsS 'http://packs.example.com:8181/packs/<full-sha1>.zip' -o pack.zip
sha1sum pack.zip
```

Expected behavior: HTTP 200, ZIP bytes with a SHA-1 identical to the pack originally sent to the client. Test one Java player while another downloads normally. See [TEST-PLAN.md](TEST-PLAN.md) for the complete matrix.

The `/pf poc` commands deliberately send bad URLs and are disabled by default. To use them on a test server, set `poc.enabled: true`, reload configuration, and use an account with `packfailover.admin`. Do not execute them on production players.

## Rollback

1. Disable `fallback.enabled` and restart, or stop Paper and remove the PackFailover jar.
2. Keep a backup of `plugins/PackFailover/config.yml` and cache if investigation is still needed.
3. Remove only the **dedicated PackFailover** Docker/FRP HTTP mappings if they are no longer needed; keep unrelated services and PacketEvents if other plugins use them.
4. Start Paper and confirm normal RPEngine uploads and resource-pack downloads continue.

For common errors, see [Troubleshooting](TROUBLESHOOTING.md).
