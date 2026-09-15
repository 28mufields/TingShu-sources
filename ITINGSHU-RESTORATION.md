# Historical ITingShu r5 restoration

## D1 diagnostic display follow-up

The user confirmed Android truncates the multiline status toast before the useful HTTP/cooldown fields. D1 presents the latest result, remaining cooldown, request count and complete path/error split into short parts as separate menu buttons and short toasts. Menu labels provide a readable snapshot when source settings are reopened. No Cookie values or response bodies are displayed. Request, playback, catalog, Cookie and rate-limit behavior is unchanged from the restored r5; this is a diagnostic build, not a claimed fix for the unconfirmed device playback failure. The historical parity statements below describe the preceding restoration commit.

## Evidence

- Historical repository JAR: `sources_by_itingshu.jar`, SHA-256 `bb45f9a987ef18e8a985823fb5f63767a70180ac4174814ff35ec3f423b71ebe`.
- GitHub decompile run: https://github.com/28mufields/TingShu-sources/actions/runs/34907406650
- Artifact ID `10373161768`, ZIP SHA-256 `3735129931eeb43f5a7e4dd3414167b551dfce098de8ce27a2c7badec4e6c490`.
- Its extracted `classes.dex` is byte-for-byte identical to the repository JAR's DEX (71,564 bytes).
- The local historical r5 JAR has the same SHA-256. The saved r5 Kotlin was checked against `AiTingShuNet`, `AiTingShuNetKt`, `SiteSession`, `SiteClient`, `SiteHttp`, and `ChapterStore` in that artifact.
- Rebuilding those saved Kotlin sources with the historical build script and checksum-verified dependencies produced **the identical 32,483-byte JAR and SHA-256**. This independently verifies exact correspondence between the readable Kotlin and the decompiled binary.
- Unified baseline: `1dfcfa0dfcdf65e983394914dec95ccb4a5c1771`.

## Restoration

The historical Kotlin implementation is retained, with only package `sources_by_itingshu` → `sources_by_28mufields`, object `AiTingShuNet` → `ITingShu`, source ID → the existing unified ID `3aa11119c74448efbd26cd3d16038bbc`, and display name → `爱听书`.

| Area | Previous unified source | Historical behavior restored |
|---|---|---|
| Search | Desktop POST for every page | Mobile session initialization; page 1 POST with `searchword`; page 2+ GET `/search/{encoded}/lastupdate/{page}.html`, `%` encoded as `oOo` |
| Search parsing | Desktop list selectors | `ol.book-ol li.book-li` and one `a.book-layout` per item; actual maximum page from mobile links |
| Catalog | Always fetched all pages; only final result cached | Follow `查看完整目录`; honor `loadEpisodes` and `loadFullPages`; never use latest ten as full catalog |
| Catalog continuation | No per-page resume, count check or cancellation | Sort/deduplicate page URLs; deduplicate episodes by URL; retain completed pages for 24h; validate final chapter count; cancel on reset/book switch |
| Pacing | Only 4s request interval | Source-wide 4s minimum plus 40 × 200ms cancellable wait per uncached later catalog page |
| Session | Cookie captured before retry loop; Set-Cookie attributes lost | Reread authoritative APP WebView Cookie for each attempt; preserve complete Set-Cookie including scope, expiry, rotation and deletion |
| Network | Search/category bypass custom client; Jsoup transport elsewhere | All site requests use historical serialized Fuel client and host-scoped session |
| Rate limiting | Numeric Retry-After; small cache | Numeric or HTTP-date Retry-After; preserved full-source cooldown; 256-page session-partitioned LRU and cached browsing during cooldown |
| Playback | Cached play pages, API JSON and final audio URLs | Fresh play-page metadata and `/api/mapi/play` on every extraction; original signature and request headers; audio UA/Referer; APP precache enabled |
| Diagnostics | No source config buttons | Historical source status and APP login explanation buttons |

The historical search does **not** contain a separate global deduplication cache or `distinctBy` on books. Restoring its correct pagination avoids the erroneous repeated-page request pattern; do not describe a newly invented deduplication algorithm as historical behavior. The unused historical `AudioCache` class is retained, but playback does not call it.

`YuetingBa.kt` and `SourceEntry.kt` remain byte-for-byte unchanged from the baseline. No change to YuetingBa's working eager chapter-tab loading.

## Validation and limits

`tests/ITingShuRegression.kt` exercises the actual restored parser/search/detail/playback code with synthetic transport responses, plus Cookie rotation/deletion, challenge retries, numeric/date 429 cooldown, full-catalog ordering, duplicate chapter removal, failure/resume, completeness checks, cancellation, and fresh playback resolution. No real credentials or website requests are used by these tests. Test host callbacks are never included in the shipped JAR.

GitHub Actions builds against official CustomSources, runs these regressions, and checks one `classes.dex`, both sources and restored helper classes. This validates compilation, packaging and deterministic source behavior. Android playback with the user's actual APP session remains a device validation step. The website's present reason for any 429 cannot be determined from code alone; 4s/8s waits are historical safeguards, not a guaranteed safe website rate.
