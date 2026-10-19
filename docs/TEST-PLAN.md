# PackFailover — Test Plan

> Stage 1 status: code/build/unit tests complete; real RPEngine end-to-end test still required on
> the actual RPEngine test server.

## 1. Automated tests

Run:

```bash
./gradlew clean build
```

Covered:

| Area | Cases |
|---|---|
| SHA-1 | full 40-hex normalization, truncation/non-hex rejection, file hashing |
| URL policy | exact host allowlist, suffix/substring attacks, strict http(s), loopback rejection, log redaction |
| Session store | config-phase adoption, generation increments, one fallback, rejoin budget reset, bounded history |
| Coordinator | FAILED_DOWNLOAD -> one same-UUID/SHA-1 fallback, no loop, DECLINED/FAILED_RELOAD no retry, dry-run, missing artifact, non-allowlisted source, required policy, HTTP-not-ready, config-phase plan, terminal status |
| Indexer | valid/invalid ZIPs, `pack.mcmeta`, fingerprint reuse, changed ZIP, old cache retention, missing provider |
| Cache | content-addressed store, SHA-1 re-check, reuse, no partial target |
| HTTP | GET/HEAD, exact bytes/headers, ETag/304, 404, 405, path traversal, no directory listing |
| Provider | max depth, glob, candidate cap, missing root, symlink not followed |
| Artifact verifier | valid ZIP, non-ZIP, missing `pack.mcmeta`, size limit, async |

The exact test count is reported by `./gradlew test` and in the build output.

## 2. Manual test matrix

| # | Scenario | Expected |
|---|---|---|
| 1 | Normal RPEngine download | No fallback, no extra prompt/delay |
| 2 | One player FAILED_DOWNLOAD, hash indexed | One fallback push, same UUID/SHA-1, new URL; RECOVERED |
| 3 | Another player downloads normally at the same time | Unaffected |
| 4 | No matching verified artifact | `skipped reason=no_verified_artifact` |
| 5 | HTTP server not ready / public URL missing | `skipped reason=http_not_ready` / `public_url_not_configured` |
| 6 | Client DECLINED | No retry |
| 7 | FAILED_RELOAD | No source switch, logged |
| 8 | Fallback also FAILED_DOWNLOAD | EXHAUSTED, no second retry |
| 9 | Player quits/rejoins during download | Old attempt cancelled, new epoch independent |
| 10 | RPEngine `/rp reload` changes SHA-1 | Old cached SHA-1 remains servable; new hash indexed |
| 11 | Same player, multiple packs | Only the failed pack retried; other packs untouched |
| 12 | Same pack UUID pushed again | New generation, old status cannot override unlimited |
| 13 | CONFIGURATION-phase pack failure | Config status observed via PacketEvents; one config-phase fallback |
| 14 | `required=true` failure | Do not claim recovery; record actual client behavior |
| 15 | Bedrock/Floodgate player | No Java pack fallback attempted |
| 16 | HTTP invalid path/method | 404/405, no file disclosure |
| 17 | Concurrent downloads | Bounded workers; Paper TPS unaffected |
| 18 | Plugin disabled | RPEngine behavior restored; HTTP closed |

## 3. Real RPEngine test preparation

1. Confirm the RPEngine output layout:
   ```bash
   find plugins/RPEngine -maxdepth 3 -type f -name '*.zip' -printf '%p %s\n'
   ```
2. Configure `providers.directories` to the exact directory.
3. Run `/pf index` and confirm the SHA-1 logged by `/pf debug <player>` matches an indexed ZIP.
4. Keep RPEngine `host.mode: upload`.
5. Force only the test player's primary download to fail (do not change global DNS/firewall).

## 4. Stage 1 sign-off

Stage 1 is not complete until one real Java client demonstrates:

- primary download failure -> one same-UUID/SHA-1 fallback -> successful load;
- RPEngine's own state/UI remains correct;
- no duplicate pack stacking or clearing of other packs;
- another player remains unaffected;
- CONFIGURATION and PLAY scenarios are both recorded (where applicable).
