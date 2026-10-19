# PackFailover

PackFailover is a Paper plugin that retries failed Java Edition resource-pack downloads using a local copy of the original pack.

When a player encounters a download failure, PackFailover sends the same resource pack from its built-in HTTP server. Successful downloads continue normally.

## Features

- Automatically retries resource packs that fail to download
- Works with RPEngine and other configured resource-pack directories
- Verifies ZIP files using SHA-1 before serving them
- Provides commands to manage packs and check fallback status

## Requirements

- Paper 26.2 with Java 25
- PacketEvents 2.14.0

## Installation

1. Install PacketEvents and place the PackFailover JAR in `plugins/`.
2. Start the server to generate `plugins/PackFailover/config.yml`.
3. Configure the original resource-pack hostname, ZIP directory, and HTTP URL.
4. Restart the server, then run `/pf index` and `/pf status`.

Example configuration:

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

http:
  port: 8181
  public-url: "http://packs.example.com:8181"
```

Edit the generated config rather than replacing it. Use your actual resource-pack hostname and ZIP directory. The `http.public-url` must be reachable by players, and its TCP port must be accessible.

See the [installation guide](docs/INSTALLATION.md) for more details.

## Commands

| Command | Description |
| --- | --- |
| `/pf status` | Show plugin status |
| `/pf index` | Rescan resource-pack ZIP files |
| `/pf cache` | List cached packs |
| `/pf debug <player>` | Show a player's resource-pack attempts |
| `/pf artifact <zip>` | Verify and cache a ZIP file |
| `/pf reload` | Reload configuration |

Commands require the `packfailover.admin` permission (granted to operators by default).

## Build

```bash
./gradlew clean build
```

The JAR is generated in `build/libs/`.

## Documentation

- [Installation](docs/INSTALLATION.md)
- [Troubleshooting](docs/TROUBLESHOOTING.md)
- [Architecture](docs/ARCHITECTURE.md)
