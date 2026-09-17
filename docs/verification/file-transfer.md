# File transfer verification

## Browser feasibility gate

- Date: 2026-09-16.
- Change: `add-file-transfer`.
- Result: native download without repeated file selection is feasible; successful delivery is acknowledged by the Android server after streaming the declared byte count.
- Browsers: installed Google Chrome 153.0.8010.48 and Microsoft Edge 153.0.4234.32, controlled through Playwright 1.63.0 branded channels.

### Native LAN HTTP download

- The isolated fixture issues a random 128-bit base64url grant and binds it to one download.
- Playwright activates the download through a normal anchor and confirms that the request is not initiated as `fetch` or `xhr`; the page never constructs a payload `Blob` or full-file `ArrayBuffer`.
- The response uses `Content-Disposition: attachment`, a fixed `Content-Length`, `Cache-Control: no-store` and `Referrer-Policy: no-referrer`.
- The saved file has the expected safe filename, byte count and SHA-256.
- The main page keeps its URL and its live event stream continues receiving heartbeats throughout the native download.
- A consumed grant and an expired grant both return HTTP 410 and cannot open another payload stream.

### Completion without repeated selection

- The page contains no secondary `input type=file`, verification button or `/verify` request.
- The server completes Android → Browser after the native response has streamed exactly the declared number of bytes.
- The browser remains on the paired page and the event stream remains connected throughout the download.
- An interrupted or short stream still fails and never produces a false `completed` state.

### Incremental SHA-256 for Browser → Android

- Selected adapter: `@noble/hashes` 2.4.0.
- License: MIT.
- Runtime dependencies: zero.
- Standalone minified adapter bundle: 7,809 bytes raw and 3,231 bytes gzip; the reproducible gate limit is 4,096 bytes gzip.
- Unit verification: standard empty/abc vectors, chunk boundaries 1, 7, 63, 64, 65 and 4,096 bytes, plus a deterministic 500 MiB stream all matched Node SHA-256.
- Browser upload preparation processed a deterministic 500 MiB stream from one reused 1 MiB chunk. Both browsers produced `47c2e1d3068c1e31e17d7d6a2787f22ce486bc224772bbdc54fff13598306290`.
- Chrome elapsed time: 9,738.7 ms. Edge elapsed time: 9,773 ms.
- With precise-memory instrumentation, sampled heap growth above the post-allocation baseline was 0 bytes in both runs. This means no retained growth was observed at checkpoints; the automated limit remains 96 MiB so an implementation that accumulates the 500 MiB payload fails.

### Reproduction

From `web/`:

```powershell
npm run benchmark:file-hash
npm run test:file-transfer-gate
./node_modules/.bin/vitest.cmd run tests/streamingSha256.test.ts
```

Observed results:

- license/bundle benchmark: passed;
- Playwright: 8 tests passed across Chrome and Edge;
- focused Vitest SHA-256 suite: 9 tests passed;
- `npm run build`: strict TypeScript check and Vite production build passed.

### Scope boundary

This gate validates the browser mechanism and the bundled digest adapter only. It does not publish production file routes, create Android storage output or claim end-to-end file transfer completion. Those parts remain in sections 2–10 of the OpenSpec task list.

## Production automated verification

- Date: 2026-09-17.
- Web unit/contract result: 26 test files and 117 tests passed.
- TypeScript and Vite production build: passed; the generated JavaScript bundle is 64.46 kB raw / 18.79 kB gzip and the stylesheet is 15.10 kB raw / 3.77 kB gzip.
- Privacy contract: production assets contain no CDN or external runtime URL, analytics, service worker, persistent browser credential storage or debug logging. File download URLs accept only one relative, scoped grant parameter and never include the session bearer.
- Chrome/Edge file-transfer gate: 10/10 tests passed. Native download without repeated selection, interrupted download failure, absence of a verify request, expired/reused grants and session-page continuity passed in both branded browser channels.
- Deterministic browser SHA-256 fixture: both Chrome and Edge produced `47c2e1d3068c1e31e17d7d6a2787f22ce486bc224772bbdc54fff13598306290` for 500 MiB. Chrome elapsed 8,296.4 ms and Edge elapsed 8,290.1 ms; sampled JS heap growth was 0 bytes in both runs.
- Android JVM result: 345 unit tests, `assembleDebug` and Android test APK assembly passed.
- Android connected result: 62/62 tests passed on API 29 and 62/62 tests passed on API 37.1.

The standalone live browser matrix must be pointed at the actual LAN endpoint shown by the app. An ADB-forwarded `127.0.0.1` URL is intentionally rejected with HTTP 403 because its `Host` header does not match the advertised LAN endpoint; supplying the advertised host confirms HTTP 200. This is expected origin/host protection, not a web-shell failure. The physical LAN browser matrix remains part of manual acceptance.

### Production 500 MiB instrumentation

`ProductionFileTransferInstrumentedTest` exercises the real production file routes through loopback HTTP on Android. Its client streams the deterministic payload as a browser would, while the server writes to a bounded digesting target or reads from a bounded generated source. The same test also transfers a zero-byte item in both directions, rejects an offer of 1 GiB + 1 byte with HTTP 413, verifies the final SHA-256, and advances a main-thread ticker throughout both 500 MiB streams.

Common SHA-256 for every 500 MiB run: `79889fe7faa77462ec5005c3bd25472a46ab221fc953307559a68dcc6cdfefd71`.

| API | Direction | Elapsed | Baseline PSS | Peak PSS | Retained PSS | Java heap peak | UI ticks |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 29 | Browser → Android | 15,295 ms | 55,723 KiB | 77,646 KiB | 24,833 KiB | 22,970,128 B | 293 |
| 29 | Android → Browser | 6,992 ms | 30,835 KiB | 46,965 KiB | 34,654 KiB | 17,963,992 B | 138 |
| 37.1 | Browser → Android | 5,381 ms | 77,006 KiB | 88,085 KiB | 80,564 KiB | 16,980,048 B | 104 |
| 37.1 | Android → Browser | 10,107 ms | 79,752 KiB | 84,488 KiB | 72,888 KiB | 14,727,280 B | 194 |

All peak deltas stayed below 128 MiB and all retained deltas stayed below 32 MiB. The UI ticker advanced during every stream, so neither production direction blocked the Android main thread.

### Cancellation and security matrix

- Coordinator tests cover queued, transferring and verifying cancellation, explicit retry with the same transfer ID, active resource closure order, browser disconnect, session revoke, network failure, server stop, queue recovery and 20 repeated cancel/stop cycles.
- Upload, download and control route tests cover premature/invalid length, owner isolation, same-origin retry, active retry conflict, pre-approval denial, upload checksum mismatch, automatic download completion, multiple-item queue preservation, unavailable post-download verification, expired/single-use grant and source/target closure.
- Storage tests cover insufficient staging space, permission loss, inaccessible/revoked destination, partial cleanup and cancellation; Compose tests keep these failures readable without leaking internal paths or stack traces.
- `DownloadGrantRegistryTest` confirms random 128-bit grants, exact generation/session/transfer scope, 30-second TTL, single use, revoke and stop invalidation.
- Static and runtime privacy checks found no token, path or content logging, no external endpoints, no service worker and no credential persistence. The trusted-network LAN HTTP warning remains visible and covered by a DOM contract test.

### Manual acceptance — 2026-09-17

Пользователь подтвердил успешную проверку на физическом телефоне и компьютере:

- single и multiple transfers в обоих направлениях, включая одновременную передачу по одному item каждого направления;
- FIFO queue, cancellation queued/active item и explicit retry с тем же item без потери остальных файлов batch;
- повторное подтверждение после отмены Android destination picker;
- refresh текущей browser tab, потерю сети, stop/start server и продолжение text flow во время file transfer;
- изоляцию нескольких browser sessions, Chrome/Edge flow, необычные имена, пустой файл и разные типы содержимого;
- keyboard navigation, light/dark theme, узкий viewport и увеличенный интерфейс.

Новых дефектов ручная приёмка не выявила. Известное ограничение browser security остаётся ожидаемым: после полного refresh страницы исходный `File` для незавершённого Browser → Android upload недоступен, поэтому UI просит выбрать его заново.

### Primary references

- [Playwright downloads](https://playwright.dev/docs/api/class-download)
- [Playwright file input](https://playwright.dev/docs/api/class-locator#set-input-files)
- [Playwright Chrome and Edge channels](https://playwright.dev/docs/browsers#google-chrome--microsoft-edge)
- [noble-hashes package and license](https://www.npmjs.com/package/@noble/hashes)
- [Blob.stream](https://developer.mozilla.org/en-US/docs/Web/API/Blob/stream)
