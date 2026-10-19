# Architecture

PackFailover provides a non-invasive second download location for Java Edition resource packs originally sent by another plugin, including RPEngine. This document describes the **implemented design**, not an implementation plan. Runtime settings are defined in [`config.yml`](../src/main/resources/config.yml).

## Data flow

```text
Other plugin (for example RPEngine) --> original ResourcePackPush
                                                |
                                          PacketEvents
                                                |
                                          PackRequestTracker
                                                |
                 Paper PLAY status / PacketEvents CONFIGURATION status
                                                |
                                     PlayerPackSessionStore
                                                |
                                       FailoverCoordinator
                                         |            |
                          SHA-1 lookup / policy    one fallback push
                                         |            |
                    VerifiedArtifactRegistry     Java client
                                         |
PackProvider --> ArtifactIndexer --> ImmutablePackCache --> HttpPackServer
                                                             |
                                                     GET /packs/<sha1>.zip
```

A successful primary request is never rewritten or replaced. A fallback request preserves the source pack UUID, hash, `required` flag, and prompt text, but points to the operator's configured HTTP origin. The prompt is reduced to plain text when captured through PacketEvents; rich formatting is not preserved.

## Components

| Package / class | Responsibility |
| --- | --- |
| `tracking/PackRequestTracker` | Observe outgoing CONFIGURATION/PLAY resource-pack requests; identify self-generated sends; receive CONFIGURATION status packets |
| `tracking/PackStatusListener` | Handle Paper `PlayerResourcePackStatusEvent` during PLAY |
| `session/PlayerPackSessionStore` | Associate player UUID, pack UUID, connection epoch, and send generation; keep bounded per-player history |
| `failover/FailoverCoordinator` | Check eligibility, state transitions, and retry budget; send a replacement URL only to the affected player |
| `provider/DirectoryPackProvider`, `RPEnginePackProvider` | Discover candidate ZIP files under administrator-selected directories |
| `index/ArtifactIndexer`, `artifact/ArtifactVerifier` | Validate ZIP files and full SHA-1 hashes asynchronously |
| `artifact/VerifiedArtifactRegistry`, `cache/ImmutablePackCache` | Resolve hash to verified bytes; retain immutable versions as resources change |
| `http/HttpPackServer` | Serve validated cache entries, never arbitrary local files |
| `config/ConfigManager`, `command/PfCommand` | Validate configuration and expose administrative diagnostics |

## Request correlation and protocol phases

Every captured request includes the player UUID, pack UUID, original URL, complete SHA-1 if supplied, `required`, prompt, protocol phase, and timestamp. A connection epoch and per-pack generation prevent older connection state from overwriting a newer request. `OutgoingSendRegistry` recognizes PackFailover's own sends so they cannot be mistaken for fresh upstream requests.

**PLAY:** Paper's `PlayerResourcePackStatusEvent` supplies the pack UUID and status. The plugin correlates it with captured send metadata rather than relying on a returned hash.

**CONFIGURATION:** Paper's PLAY status listener alone is insufficient. PacketEvents receives CONFIGURATION resource-pack status packets, and the coordinator can send `WrapperConfigServerResourcePackSend`. This path is implemented and unit tested, **not yet validated by a real-client end-to-end CONFIGURATION test**.

Resource-pack status messages have no explicit send-generation field. Correlation is therefore bounded by currently tracked pack UUID/session state and is not an absolute guarantee against delayed status ambiguity.

## Automatic fallback

A primary request is considered for fallback **only** after `FAILED_DOWNLOAD`. The coordinator requires:

- a valid 40-character SHA-1 corresponding to a locally verified ZIP;
- a source hostname on the exact-match allowlist;
- a usable immutable cached file and ready, configured public HTTP endpoint;
- an active applicable player/session, enabled retry policy, and an available retry budget;
- Java Edition handling; Floodgate users are excluded when detection is available.

The usual sequence is:

```text
PRIMARY_PENDING --> PRIMARY_FAILED_DOWNLOAD
                       |
                       | eligible
                       v
                 FALLBACK_PENDING
                   |          |
        SUCCESSFULLY_LOADED   FAILED_DOWNLOAD
                   |          |
                RECOVERED   EXHAUSTED
```

`DOWNLOADED` is not equivalent to `SUCCESSFULLY_LOADED`. `DECLINED`, `FAILED_RELOAD`, `INVALID_URL`, and `DISCARDED` are not triggers for automatic fallback. A failed fallback does not recursively trigger another fallback.

The release's intended automatic policy is **one retry per pack**. Although the current configuration parser accepts values larger than one, the fallback state path does not implement a chain of multiple automatic retries. Treat `0` as disabled and `1` as the supported retry budget.

For PLAY sends, `Player#addResourcePack` is used rather than `setResourcePack`: the latter clears existing resource packs on the tested Paper API. This matters when RPEngine stacks multiple packs.

## Files and HTTP service

Provider roots, globs, candidate limits, and traversal depth come from `providers.directories`. Files are inspected outside the game thread. A candidate must be a valid ZIP containing `pack.mcmeta` and must meet the configured size limit. Hashes are computed on **entire ZIP bytes**, and the immutable cache stores entries by full SHA-1.

The built-in HTTP service serves `GET` and `HEAD` for `/packs/<40-lowercase-hex>.zip`, including standard content headers and ETag support. Unmatched paths and unsupported methods are rejected. There is no generic file browser, upload endpoint, or arbitrary URL forwarding. Operators must keep the artifact source directories trusted; SHA-1 is a protocol identity check, not a substitute for authenticating uploaded files.

The HTTP bind address (inside a Docker container, often `0.0.0.0:8181`) differs from `http.public-url`, which **must** be reachable by the Minecraft client. See [Installation](INSTALLATION.md).

## Threading and lifecycle

PacketEvents callbacks capture lightweight metadata; file verification, hashing, indexing, and caching run off-thread. Paper player APIs are used in the server's proper scheduler context. Player quit/rejoin events advance or retire session state; plugin shutdown unregisters listeners and stops HTTP/indexing services.

This project targets Paper 26.2, **not Folia**, and does not claim universal compatibility across Minecraft protocol versions.

## Verified behavior and limitations

The October 19, 2026 integration record demonstrates a real RPEngine PLAY-phase `FAILED_DOWNLOAD` on the original pack UUID, followed by one fallback to a byte-identical local ZIP and a `SUCCESSFULLY_LOADED` result. It also verifies that RPEngine's published full SHA-1 matched the discovered ZIPs.

That does **not** establish all compatibility cases. Still outstanding:

- CONFIGURATION-phase recovery with a real Java client;
- mandatory (`required=true`) pack recovery before a disconnect;
- consistent upstream RPEngine UI/status after fallback: RPEngine may still log the original failure;
- independently failing one pack while other RPEngine stack members remain healthy;
- wider compatibility across versions and other resource-pack providers.

See [Test Plan](TEST-PLAN.md) and the [historical protocol report](development/PROTOCOL-POC.md), especially its most recent Section 15.11–15.13. Earlier status tables in that report are historical snapshots and may have been superseded.
