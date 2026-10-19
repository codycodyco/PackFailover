# Test plan and validation status

This document separates **automated coverage**, **recorded real-client evidence**, and **outstanding integration tests**. The historical engineering log is archived at [development/PROTOCOL-POC.md](development/PROTOCOL-POC.md). Earlier entries in that log may have been superseded; consult Sections 15.11–15.13 for the most recent PLAY verification.

## Automated checks

Run with Java 25:

```bash
./gradlew clean build
```

The repository includes JUnit tests for:

- SHA-1 normalization, full-file hashing, and artifact verification;
- source-host allowlists and URL validation;
- per-player sessions, connection epochs, generations, retry budgets, and stale-state handling;
- fallback coordinator policies (eligible downloads, disabled/dry-run, missing artifacts, terminal results);
- indexing, provider traversal, immutable cache, and HTTP GET/HEAD/error behavior.

The historical report records **72 passing JUnit tests** for its final development state. This is a **reported past run**, not a fresh CI result or verification performed during documentation cleanup.

## Real Java-client evidence (2026-10-19)

| Scenario | Status | Evidence / limit |
| --- | --- | --- |
| RPEngine 0.2.20 PLAY push captured with UUID and full SHA-1 | Verified | Pack metadata and local output matched |
| Original RPEngine pack UUID fails download, then local HTTP fallback succeeds | Verified | `FAILED_DOWNLOAD` -> fallback -> `DOWNLOADED` -> `SUCCESSFULLY_LOADED` |
| Same original UUID and same SHA-1, different URL | Verified for PLAY | A real Java client used the fallback ZIP |
| Successful source download left untouched | Supported by code/tests | Record a controlled comparison with a second player before wider release |
| Other stacked RPEngine packs unaffected by one-pack failure | Partial | A previous exercise kept two packs loaded, but the strict failure exercise caused the rigs pack to be discarded |
| CONFIGURATION phase, real client | Not verified | Implementation and unit tests only |
| `required=true`, real client | Not verified | Client may disconnect before the retry completes |
| RPEngine upstream status/UI synchronized after fallback | Not verified | Upstream still logged its original download failure |
| Reload, multi-player, reconnect stress testing | Not fully verified | Regression scenarios remain |

## Manual regression matrix

| Scenario | Expected behavior |
| --- | --- |
| Primary download succeeds | No fallback push |
| One eligible `FAILED_DOWNLOAD` | Exactly one retry to the verified local ZIP |
| Another player downloads normally | No additional push to that player |
| No complete SHA-1 or verified artifact | No fallback; log a skip reason |
| Source hostname not allowlisted | No fallback |
| HTTP unavailable or public URL empty | No fallback |
| `DECLINED` or `FAILED_RELOAD` | No retry |
| Fallback download fails | End attempt; no retry loop |
| Player disconnects or rejoins | Previous session cannot affect the new session |
| Upstream pushes same UUID again | New generation replaces the old tracked attempt |
| RPEngine regenerates ZIPs on reload | New hash indexed; active old cache stays servable |
| Multiple packs stacked | Only the failed pack is retried; other packs are not popped |
| CONFIGURATION phase | Capture and status correlation, then validated fallback |
| Mandatory pack failure | Record actual disconnection and retry ordering |
| Bedrock/Floodgate client | No Java pack fallback when detected |
| Invalid HTTP path or method | No arbitrary file disclosure |
| PacketEvents missing/unavailable | Clear diagnostics; no unhandled server crash |
| Plugin disabled | Original provider behavior is unchanged |

**End-to-end acceptance:** Run tests on a non-production server with a real Java client, matching verified ZIP and reachable HTTP server. Capture client behavior, HTTP requests, pack UUID, SHA-1, and both the primary and fallback statuses. Do not infer a real download from a client-side SHA-1 cache hit; use a fresh pack hash or clear the test client cache.

## Release blockers

The core PLAY path has real-client evidence; general compatibility is **not** established. Before presenting CONFIGURATION and mandatory pack support as validated, execute corresponding real-client tests. Investigate RPEngine's own failure state when a downstream fallback succeeds, and test a failing main pack with a healthy rigs pack.

Keep raw logs and timed test notes in a development log, not inline in installation documentation.
