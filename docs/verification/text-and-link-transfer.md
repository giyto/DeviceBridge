# Text and link transfer verification

## Automated implementation verification

- Date: 2026-09-16.
- Change: `add-text-and-link-transfer`.
- Browser implementation uses the existing authenticated local session; no second Android application or external backend was added.
- Text is generation-scoped and bounded to 100 items. Full content is not persisted to Room, DataStore, files, backup or saved instance state.
- Browser credentials remain in the current tab session and are sent only in the `Authorization` header or first WebSocket `session.auth` frame.

### Web verification

- `npm.cmd test` from `web/` completed successfully after a production prebuild.
- Result: 17 test files, 84 tests, 0 failures.
- Strict TypeScript typecheck and `npm.cmd run build` completed successfully.
- Built assets contain no external runtime origin, analytics integration or service-worker registration.
- Browser identity fixtures verify product-specific priority: YaBrowser, Edge, Opera, Firefox, Chrome/Chromium, Safari and neutral fallback.
- Text API tests cover the exact `text.send` schema, Bearer header, token-free URL, `401`, `409`, `413` and abort.
- WebSocket tests cover auth-before-events, bounded reconnect, session loss, snapshot callbacks, live-event deduplication and `text.ack`.
- LAN HTTP regression tests verify WebSocket authentication and browser text sending
  when secure-context-only `crypto.randomUUID()` is unavailable.
- DOM tests cover keyboard form submission, accessible labels, sender/time/direction/status, plain-text rendering of HTML-like payloads, explicit copy with manual fallback and explicit safe HTTP(S) opening without auto-navigation.

### Android and packaged-assets verification

- `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug` completed successfully.
- The debug APK packages the current generated web assets.
- API 29 `Pixel_4`: 44/44 instrumented tests passed, 0 skipped, 0 failed.
- API 37.1 `Pixel_8_API_37_1_DeviceBridge`: the first attempt executed 0 tests because the Android 17 preview `system_server` crashed inside `AppOpsManager` and removed the package/activity services. A cold boot without loading the AVD snapshot restored the services; the retry passed 44/44 tests, 0 skipped, 0 failed.

## Server integration checklist

The focused command selected `TextRouteTest`, `TextWebSocketRouteTest`, the four text-coordinator suites and `KtorServerRuntimeSessionTest`.

- [x] Browser → Android: authenticated `POST /api/v1/text` creates one incoming item and returns `text.accepted`.
- [x] Android → Browser: only the selected one of two simultaneous browser sessions receives `text.received`; the other receives nothing.
- [x] Acknowledgement: `text.ack` completes the selected delivery as `DELIVERED`.
- [x] Idempotent retry: the same `messageId` and payload returns the original result without a duplicate.
- [x] Conflict: the same `messageId` with different content returns HTTP 409 without mutating the feed.
- [x] Revoke: a revoked session fails its pending delivery and cannot receive another message.
- [x] Stop/generation close: pending text fails, current text state is cleared and stale generation input is rejected.
- [x] Restart/replacement semantics: replacing the generation clears the old feed/outcomes and old generation identifiers cannot mutate the new generation.
- [x] Missing and unknown Bearer credentials return HTTP 401 before creating text state.
- [x] Exactly 102400 UTF-8 bytes is accepted.
- [x] 102401 bytes returns HTTP 413 and does not create the rejected item.
- [x] Session snapshot is scoped to one browser session and does not duplicate a repeated `messageId`.

Focused result: 7 suites, 27 tests, 0 failures, 0 errors, 0 skipped.

## Manual acceptance checklist

User acceptance was completed on 2026-09-16 with the current debug APK and current
desktop browsers.

- [x] Pair and verify the exact label in current Chrome on Windows.
- [x] Pair and verify the exact label in current Edge on Windows.
- [x] Pair in current Yandex Browser and verify that Android shows «Яндекс Браузер», not Chrome.
- [x] Send plain text, Cyrillic, emoji, multiline text and an HTTP(S) link Browser → Android.
- [x] Send the same content types Android → one selected browser and verify that a second session receives nothing.
- [x] Verify sender, time, direction and status in Android and browser feeds.
- [x] Refresh the authorized tab and verify session restoration plus a bounded, duplicate-free snapshot.
- [x] Revoke the session on Android and verify that the browser returns to pairing.
- [x] Verify explicit Android clipboard paste and the Android `ACTION_SEND text/plain` share target without automatic sending.
- [x] On LAN HTTP, click «Копировать» and verify the selectable manual fallback without a false success message.
- [x] Verify that receiving an HTTP(S) link never opens it automatically and only an explicit «Открыть ссылку» action navigates.
- [x] Verify that file controls remain disabled and unfinished file/transfer/diagnostic routes return 404.
- [x] Stop and restart the Android server; verify that old browser credentials cannot authorize the new generation.
