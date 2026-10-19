# Troubleshooting

Use `/pf status`, `/pf index`, `/pf cache`, and `/pf debug <player>` from a permitted administrator account or the server console.

## Fallback never starts

- Confirm `enabled`, `fallback.enabled`, and `fallback.dry-run` in `config.yml`.
- Check the original pack URL's exact hostname is in `tracking.primary-host-allowlist`. The entry must not contain a scheme or port.
- Fallback reacts to `FAILED_DOWNLOAD`, **not** `DECLINED`, `FAILED_RELOAD`, or other client statuses.
- Check PacketEvents 2.14.0 is installed and the capture hook shows ready.
- Verify the client is a Java Edition player; Floodgate clients are skipped when detection is available.

## No verified artifact

- Confirm `providers.directories` points to the ZIP output actually used by the pack provider.
- Run `/pf index` and check its indexed/rejected counts.
- The original request must contain a **complete 40-hex SHA-1**, equal to the verified ZIP bytes. A short hash or a recreated ZIP with different bytes will not match.
- Source files should be valid ZIPs containing `pack.mcmeta`. Avoid indexing entire `plugins/` trees or private folders.

## HTTP endpoint not ready / download fails

- Set `http.public-url` to a host and port the **player** can reach. `127.0.0.1` means the player's own computer and is not suitable for a remote player.
- Confirm the listener bind address, Docker port publishing, FRP forwarding, and FRPS firewall are aligned.
- Test from an **external** device using `curl -I` and then download the full ZIP; compare its SHA-1.
- If the HTTP port or provider directory changed, **restart** Paper. `/pf reload` alone does not reconstruct those runtime services.

## Client reports success but no HTTP GET appears

Minecraft may reuse a cached pack identified by its UUID and SHA-1. That does not prove the fallback endpoint was downloaded. For an end-to-end test, use a newly generated hash or carefully clear the test client's server resource-pack cache.

## RPEngine still reports a download failure

In the October 19, 2026 strict PLAY test, the client loaded the fallback successfully but RPEngine still recorded the failed original download. PackFailover does not currently synchronize RPEngine's internal status/UI. Judge client recovery and upstream status separately.

## CONFIGURATION and mandatory packs

These paths are implemented or configurable but lack complete real-client verification. A mandatory pack may disconnect a player before a retry is possible. For a conservative deployment, use `fallback.config-phase: false` and `fallback.allow-required: false` until you have tested them.

## Diagnostic commands

`/pf poc` tools simulate or replay failures with intentionally bad URLs. They are for a controlled test server, not for production-player troubleshooting. They require both `packfailover.admin` and `poc.enabled: true` (disabled by default); enable only on a controlled test server and run `/pf reload` after changing the setting.

See [Test Plan](TEST-PLAN.md) and [Installation](INSTALLATION.md).
