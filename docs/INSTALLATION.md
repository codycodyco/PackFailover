# PackFailover — Installation and Rollback

> Target: Paper 26.2 / Java 25. Test server first; do not modify production Docker Compose or
> the production FRP configuration without an explicit review.

## 1. Requirements

- Paper 26.2 on Java 25.
- PacketEvents **2.14.0** installed as a plugin (the release bundle, not the thin Maven artifact).
- A client-reachable HTTP endpoint for fallback packs, e.g. `http://packs.example.com:8181`.
- RPEngine still configured with `host.mode: upload`; PackFailover must not replace it.

## 2. Build

```bash
./gradlew clean build
```

Artifact:

```text
build/libs/PackFailover-0.1.0-SNAPSHOT.jar
```

## 3. Install

1. Copy the jar into `plugins/`.
2. Install PacketEvents 2.14.0 into `plugins/`.
3. Start the server once to generate `plugins/PackFailover/config.yml`.
4. Stop the server and edit the configuration.
5. Start the server and verify the log.

## 4. Configuration

Minimal production-shaped configuration:

```yaml
enabled: true

tracking:
  track-config-phase: true
  track-play-phase: true
  primary-host-allowlist:
    - "packs.resourcepack.ai"

providers:
  reindex-seconds: 60
  max-candidates-per-provider: 200
  directories:
    - id: rpengine
      enabled: true
      # VERIFY this path on the actual RPEngine version.
      path: "plugins/RPEngine/output"
      glob: "**/*.zip"
      max-depth: 3

fallback:
  enabled: true
  dry-run: false
  max-attempts-per-pack: 1
  config-phase: true
  allow-required: true

http:
  enabled: true
  bind-address: "0.0.0.0"
  port: 8181
  public-url: "http://packs.example.com:8181"
  worker-threads: 4
  backlog: 32
  cache-control-seconds: 3600

cache:
  directory: "plugins/PackFailover/cache"

artifact:
  max-size-mb: 100

poc:
  bad-url-template: "http://127.0.0.1:1/{sha1}.zip"

logging:
  debug: false
```

### 4.1 RPEngine output path

The provider path is intentionally configurable. Verify it on the actual RPEngine version:

```bash
find plugins/RPEngine -maxdepth 3 -type f -name '*.zip' -printf '%p %s bytes\n'
```

If the ZIPs are not under `plugins/RPEngine/output`, update `providers.directories[0].path`.
Do not point the provider at a directory containing credentials, worlds, or unrelated files.
`**/*.zip` also matches ZIPs directly at the provider root.

### 4.2 Public URL

`http.public-url` is the **client-facing** base URL, not the container bind address:

```text
fallback URL = <public-url>/packs/<full-sha1>.zip
```

Loopback/unspecified addresses are rejected. The HTTP server binds `bind-address:port`
(`0.0.0.0:8181` inside a container; `127.0.0.1:8181` when only a local FRP client needs it).

### 4.3 First-run checks

```text
/pf status
/pf index
/pf cache
```

Expected log lines:

```text
[PackFailover] packetevents_hooked version=2.14.0
[PackFailover] http_started bind=0.0.0.0:8181 publicUrl=http://...
[PackFailover] provider_configured id=rpengine root=...
[PackFailover] index_done ... indexedSha1=1
```

Verify the HTTP endpoint from outside the host:

```bash
curl -I http://packs.example.com:8181/packs/<sha1>.zip
curl -s http://packs.example.com:8181/packs/<sha1>.zip | sha1sum
```

## 5. Docker Compose (append only)

Do **not** overwrite the existing `ports:` list. Append an 8181 mapping:

```yaml
services:
  paper:
    ports:
      - "127.0.0.1:8181:8181/tcp"
```

Inside the container, set `http.bind-address: "0.0.0.0"`. The public URL remains the
client-reachable FRPS/reverse-proxy address.

## 6. FRP mapping (append only)

In the **test or dedicated** `frpc.toml`:

```toml
[[proxies]]
name = "packfailover-http-8181"
type = "tcp"
localIP = "127.0.0.1"
localPort = 8181
remotePort = 8181
```

Validate:

```bash
frpc verify -c frpc.toml
```

Then allow `8181/TCP` on the FRPS security group/host firewall. Do not modify the production
FRP configuration as part of a test deployment.

## 7. Verification

- `/pf status` shows `http=ready`, non-zero `indexed`, and the expected `publicUrl`.
- `/pf cache` lists `<sha1>.zip` files.
- A real client with a forced `FAILED_DOWNLOAD` receives exactly one fallback push with the same
  pack UUID and SHA-1.
- Successful players receive no extra pack, no prompt, and no delay.
- `DECLINED` and `FAILED_RELOAD` do not trigger a source switch.
- A second `FAILED_DOWNLOAD` does not cause a second retry.
- Bedrock/Floodgate players are skipped.

See [TEST-PLAN.md](TEST-PLAN.md) for the full matrix.

## 8. Rollback

1. Stop the test server.
2. Remove `plugins/PackFailover-0.1.0-SNAPSHOT.jar` and `plugins/PackFailover/`
   (keep a backup of the config if needed).
3. If PacketEvents was installed only for this test, remove it as well.
4. Restore the previous `frpc.toml` / Compose file from your backup.
5. Start the server and confirm RPEngine behaves as before.

The plugin never modifies RPEngine files, worlds, or the production Compose/FRP configuration.
