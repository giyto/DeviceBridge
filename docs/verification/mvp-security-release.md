# Проверка безопасности и release readiness MVP

## Candidate identity

This report starts the evidence chain for OpenSpec change `prepare-mvp-security-release`.

| Field | Baseline value |
| --- | --- |
| Captured | 2026-09-20 |
| Commit | `e5115a75a19d2919958b5349aabc39f904ffcf3b` |
| Android application ID | `ru.hznik.devicebridge` |
| versionCode / versionName | `2` / `1.0.1` |
| minSdk / targetSdk / compileSdk | 29 / 37 / 37 |
| Build types | `debug`, `release` |
| Release signing baseline | none; `:app:signingReport` reports `Config: none` |
| Current release signing | external RSA-4096 release key; one signer; APK Signature Scheme v2 verified |
| Current signed artifact | `app-release.apk`; SHA-256 `cbe62b03c5e2e755d6eaa310648a1c5fc552b02d16b2a41b28b7f27425a2e7d8` |
| Distribution verdict | `BLOCKED_DIRTY`: signing passes, but the implementation worktree is not committed |
| Bundled web manifest | protocol `1`, asset version `sha256-235ba8ea862c8476` |
| Runtime engine | Ktor CIO 3.5.2 |
| Android build stack | AGP 9.3.2, Kotlin 2.2.10, Gradle 9.5.0, JDK 21.0.7 |
| Web build stack | Node 22.15.0, npm 10.9.2, Vite 8.3.0, TypeScript 7.0.2 |

The report intentionally omits keystore paths, certificate fingerprints, credentials, pairing codes, user payloads, private machine paths, and raw logs.

## Baseline surface

### Packaged web assets

`buildWebAssets` runs the locked production web build before Android asset and lint tasks, deletes the previous generated output, and rejects output missing `index.html`, `asset-manifest.json`, `web-manifest.json`, or generated assets. The current generated bundle contains only local HTML, CSS, JavaScript, and manifests. Generated Android assets and build outputs are not release evidence until rebuilt from the candidate commit.

### Android permissions

The main manifest declares `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `ACCESS_LOCAL_NETWORK`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `CHANGE_NETWORK_STATE`, and `WAKE_LOCK`. The foreground service is not exported. Backup rules exclude the Room database, preferences DataStore, and server session journal from cloud backup and device transfer.

### Production route graph

| Access | Routes |
| --- | --- |
| Public | `GET /`, `GET /web-manifest.json`, `GET /assets/*`, `POST /api/v1/session/challenge`, `POST /api/v1/session/confirm` |
| Bearer protected | `GET /api/v1/status`, `DELETE /api/v1/session`, `POST /api/v1/text`, file create/upload/grant, transfer cancel/retry |
| Auth-first WebSocket | `GET /api/v1/events` |
| One-time grant | file download by transfer ID |
| Not found | every unlisted path, including `/diagnostics/*` in production |

Debug diagnostics live only in `src/debug`. Production composition in `src/main` does not register diagnostics or accept a diagnostics token. Existing runtime tests confirm a safe `404` for `/diagnostics/health` and `401` for an unauthenticated protected status request.

### Runtime network destinations

The production server binds a dynamically selected local endpoint and the bundled web client uses same-origin HTTP/WebSocket requests to that endpoint. Source inspection found no production backend, analytics, CDN, remote font, update, or telemetry destination. XML namespace identifiers and preview/test example URLs are not runtime destinations. A final APK/browser egress inspection remains mandatory before a `READY` verdict.

## Threat, control, and evidence matrix

`Critical` and `High` failures block release. Manual gates may support but never replace required automated evidence.

| Threat / protected property | Required control | Owner | Gate / evidence | Severity | Baseline |
| --- | --- | --- | --- | --- | --- |
| Listener remains reachable outside an explicit run | Server exists only during an explicit session | Android lifecycle | lifecycle tests; stopped-port inspection | Critical | Existing; candidate rerun pending |
| Unapproved API access | All non-public routes require bearer authorization | Server protocol | route policy and negative HTTP tests | Critical | Existing; candidate rerun pending |
| Pairing-code guessing or replay | Short TTL and bounded attempts | Session security | expiry, attempt and generation tests | High | Existing; candidate rerun pending |
| Silent browser enrollment | Phone approval is mandatory | Session coordinator | approve/deny/timeout tests | Critical | Existing; candidate rerun pending |
| Cross-origin request | No permissive CORS; exact Origin checks | Web transport | Host/Origin/CORS tests | High | Existing; candidate rerun pending |
| Host confusion or malformed upload | Validate Host, Origin, MIME, size and safe path/name | File transport | negative route/file tests | Critical | Existing; candidate rerun pending |
| Script injection from received text | Render as text, never HTML | Web UI | rendering tests and bundle inspection | High | Existing; candidate rerun pending |
| Credential or payload disclosure | Do not log transfer content, codes, or tokens | Release engineering | source, artifact and report scans | Critical | Gate to be added |
| Predictable credentials | Cryptographically secure randomness | Session security | generator/credential tests | Critical | Existing; candidate rerun pending |
| Trusted credential theft/reuse | Encrypted/verifier storage, expiry and revoke | Persistence/session | repository and revoke tests | Critical | Existing; candidate rerun pending |
| Access survives network/permission loss | Close listener and invalidate generation | Android lifecycle | network/permission tests | Critical | Existing; candidate rerun pending |
| LAN observer reads traffic | Honest warning and short-lived credentials | Product/security | UI/docs/offline LAN smoke | High residual | Accepted by ADR-0001 |
| Debug surface reaches release | Variant-isolated diagnostics and safe 404 | Build/server | contract tests and APK inspection | Critical | Tests exist; APK pending |
| Tampered or unidentified APK | External signing and artifact identity | Release engineering | signing gate, apksigner, SHA-256 | Critical | Signed candidate, clean install и совместимое update PASS; clean-commit gate остаётся |
| External runtime traffic | No backend, analytics, CDN, or telemetry | Android/web release | source/APK scan and egress test | Critical | Source clean; observation pending |
| Corrupt or false-success transfer | Streaming checksum and terminal validation | File transfer | automated tests and 500 MB matrix | High | Manual candidate run pending |

## Текущие blockers и следующие gates

- Production release signing настроен вне repository; fail-closed Gradle gate и `apksigner verify` проходят без debug fallback.
- `BLOCKED`: подписанный APK собран из worktree с ещё не зафиксированной реализацией, поэтому metadata имеет статус `BLOCKED_DIRTY`.
- Текущий signed candidate `2/1.0.1` прошёл clean install и полный release smoke на API 29 и API 37.1.
- Update `1/1.0` → `2/1.0.1` с тем же release certificate прошёл на API 37.1. Сохранены settings, trusted browser record, history item и SAF destination.
- `BLOCKED`: Windows 10/11 signed-candidate acceptance и наблюдаемый offline egress ещё не закрыты.
- Автоматические 500 MiB streaming/checksum tests пройдены в обе стороны на Android и в Chrome/Edge, но реальный signed-candidate network-interruption run остаётся обязательным.
- `READY` is forbidden while any required cell is `NOT RUN`, `FAIL`, or `BLOCKED`, or while any critical/high finding is unresolved.

## Implementation evidence

- The release isolation suites passed 10/10 tests across `ProductionRoutePolicyTest`, `KtorServerRuntimeFactoryTest`, and `ReleaseWebIsolationContractTest`.
- `:app:assembleRelease` completed with the production Vite build. APK inspection found the five required `assets/web/*` entries, matching protocol/web asset metadata, `debuggable=false`, and zero `ru.hznik.devicebridge.diagnostics` DEX matches.
- `verifyReleaseSigningConfiguration` fails closed when any of the four external signing properties are absent and reuses the Gradle configuration cache without serializing task secret inputs. `apksigner verify` rejects the current unsigned artifact as expected.
- A local signing verification used a randomly generated 30-day RSA-2048 key stored only under the system temporary directory and supplied through external Gradle properties with configuration cache disabled. `assembleDistributionRelease` passed, `apksigner` verified one signer with APK Signature Scheme v2, and metadata reported `BLOCKED_DIRTY`; signed APK SHA-256 was `ef3463cec458f0f53c5a3ebe511ff9ab18039f59d5dfd768ccf8c16ab46f49f9`. The key and signed APK were then destroyed, so this proves the signing pipeline but is not a distributable production certificate.
- A persistent RSA-4096 release key was created outside the repository and supplied through the active Gradle user home. `verifyReleaseSigningConfiguration` and `assembleDistributionRelease` passed; `apksigner` verified one v2 signer. No key path, password, alias value, or certificate fingerprint is recorded in repository evidence.
- `generateReleaseMetadata` produced signed APK SHA-256 `cbe62b03c5e2e755d6eaa310648a1c5fc552b02d16b2a41b28b7f27425a2e7d8`, web asset version `sha256-235ba8ea862c8476`, version `2/1.0.1`, and commit `e5115a75a19d2919958b5349aabc39f904ffcf3b`. It reports `BLOCKED_DIRTY` because implementation changes are not committed; this is evidence, not a publishable verdict.
- Release versions are externally configurable through safe Gradle properties. The contract test was observed RED before implementation and the complete release build configuration suite passed after the change.
- The current signed APK completed clean install and cold launch on API 37.1 with an empty crash buffer. The previous `1/1.0` candidate had already completed clean launch on API 29 and API 37.1.
- The API 37.1 update gate used two APKs signed by the same RSA-4096 certificate: SHA-256 `cf3643ecc8794eb7f53f883aba9558883223611e2ddac1750c3b5b077760b1dc` (`1/1.0`) and `cbe62b03c5e2e755d6eaa310648a1c5fc552b02d16b2a41b28b7f27425a2e7d8` (`2/1.0.1`). `adb install -r` succeeded; retention `31`, maximum file size `900 MiB`, one trusted browser record, one delivered history item and the persisted SAF destination remained visible after cold launch.
- The signed `2/1.0.1` candidate completed the full API 37.1 release smoke: local-network permission deny/retry/allow recovery, foreground notification, listener stop/restart, real pairing, delivered text and link, two sequential accepted files persisted through SAF, Share Target draft/recipient, history/settings and lifecycle. The crash buffer remained empty.
- The same signed candidate completed the full API 29 release smoke: foreground notification, listener stop/restart, real pairing, delivered text and link, two sequential accepted files persisted through SAF, Share Target draft/recipient, history/settings and lifecycle. Runtime local-network permission is not applicable on API 29. After stop the listener was closed and active DeviceBridge notification count was zero; the crash buffer remained empty.
- The complete Android JVM/Ktor suite passed 482/482 tests in 141 suites with zero failures, errors, or skips. This reruns the negative route, pairing/trust, text/file boundary, lifecycle, persistence, and release build contracts against the locked graph.
- Android Compose/instrumentation passed 116/116 on API 29 and 116/116 on API 37.1 AVDs started with 4 GiB RAM. A run on Xiaomi 12T Pro/API 35 was not counted: MIUI denied the test `ComponentActivity`, so it was an environment blocker rather than a product result.
- The web suite passed 211/211 tests in 39 files and strict TypeScript typecheck plus the production Vite build passed.
- Playwright visual passed 51 applicable tests with 9 configuration skips; compatibility passed 6/6 in installed Chrome `153.0.8010.50` and Edge `153.0.4234.32`; file-transfer passed 14/14 including deterministic 500 MiB SHA-256 in both browsers.
- The release policy scanner passed 2/2 scanner fixtures and eight production/configuration roots. It blocks private keys and secret literals, external runtime URLs, analytics/CDN markers, service-worker registration, private absolute paths, and credential/payload logging while allowing documentation URLs in comments.

## Точные автоматические команды и результаты

| Gate | Команда | Результат 2026-09-20 |
| --- | --- | --- |
| Android JVM/Ktor, lint, release, policy, metadata | `.\\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleRelease :app:verifyReleasePolicy :app:generateReleaseMetadata` | PASS: 482/482; lint 0 errors/66 warnings; 96 tasks; release APK создан |
| Signed update candidate | `.\\gradlew.bat :app:assembleDistributionRelease '-PdeviceBridgeVersionCode=2' '-PdeviceBridgeVersionName=1.0.1' '-PdeviceBridgePreviousVersionCode=1' --no-configuration-cache` | PASS: signed `2/1.0.1`, monotonic metadata и production web assets |
| Compose/instrumentation API 29 | `ANDROID_SERIAL=emulator-5554 .\\gradlew.bat :app:connectedDebugAndroidTest --no-parallel` | PASS: 116/116, 0 failed, 0 skipped, 4m44s |
| Compose/instrumentation API 37.1 | `ANDROID_SERIAL=emulator-5554 .\\gradlew.bat :app:connectedDebugAndroidTest --no-parallel` | PASS: 116/116, 0 failed, 0 skipped, 5m49s |
| Web typecheck | `npm run typecheck` | PASS |
| Web unit | `npm test` | PASS: 211/211, 39 files |
| Web production bundle | `npm run build` | PASS: 38 modules; only local bundle assets |
| Visual | `npm run test:visual` | PASS: 51 applicable, 9 configuration skips |
| Chrome/Edge compatibility | `npm run test:compatibility` | PASS: 6/6 |
| File transfer | `npm run test:file-transfer-gate` | PASS: 14/14; Chrome/Edge 500 MiB digest `47c2e1d3068c1e31e17d7d6a2787f22ce486bc224772bbdc54fff13598306290` |
| Release policy | `npm run verify:release-policy` | PASS: 2/2 fixtures, 8 roots |

## APK inspection текущего signed candidate

- Application ID `ru.hznik.devicebridge`, version `2` / `1.0.1`, `debuggable=false`.
- SHA-256: `cbe62b03c5e2e755d6eaa310648a1c5fc552b02d16b2a41b28b7f27425a2e7d8`.
- `web-manifest.json` и `asset-manifest.json` внутри APK byte-for-byte совпадают с merged release assets; protocol `1`, `webAssetVersion=sha256-235ba8ea862c8476`.
- Diagnostics DEX matches: `0`; release policy scan: PASS.
- Native libraries присутствуют для `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`.
- Permissions: local network/network state/Wi-Fi, foreground connected-device service, notifications и wake lock; broad storage permission отсутствует.
- Production signing: PASS; один RSA-4096 signer, v2 signature, `apksigner verify` PASS. Signing material хранится вне repository; fingerprint, путь и credentials в evidence не записываются.

## Dependency review

| Ecosystem | Lock/source | Result | Finding classification |
| --- | --- | --- | --- |
| Web runtime and tooling | committed `package-lock.json`; npm registry audit on 2026-09-20 | 0 info/low/moderate/high/critical findings across runtime and full toolchain | PASS |
| Android application graph | version catalog plus committed `app/gradle.lockfile` (482 entries) | release/debug/test transitive selections are now reproducible; Ktor runtime remains 3.5.2 | PASS |
| Gradle settings/plugins | committed `settings-gradle.lockfile` | plugin/settings resolution state captured | PASS |
| Android advisory coverage | resolved dependency graph only | no first-party vulnerability database result is inferred from dependency resolution | Medium coverage limitation; repeat advisory review immediately before publication |

The unresolved Android advisory-coverage limitation is not evidence of a known vulnerable component, but it prevents this dated review from being treated as permanent. Any later critical/high advisory is a release blocker.

## Обязательная acceptance-матрица

| Ячейка | Статус | Evidence / причина |
| --- | --- | --- |
| API 29 release clean install/smoke | `PASS` | signed `2/1.0.1`; start/stop/notification, pairing, text/link, two-file sequence, Share Target, history/settings, SAF и lifecycle PASS; local-network runtime permission N/A |
| API 37.1 release clean install/smoke | `PASS` | signed `2/1.0.1`; start/stop/notification, permission recovery, pairing, text/link, two-file sequence, Share Target, history/settings, SAF и lifecycle PASS |
| Update поверх предыдущей release | `PASS` | `1/1.0` → `2/1.0.1`, одинаковый release certificate; settings, trusted browser, history и SAF destination сохранены |
| Chrome/Edge Windows 11 | `BLOCKED` | automated gates PASS; signed-candidate manual acceptance не выполнен |
| Chrome/Edge Windows 10 | `NOT RUN` | среда не проверена для текущего artifact |
| 500 MiB обе стороны / checksum | `BLOCKED` | Android production-route и browser benchmark automation PASS; реальный signed-candidate flow не выполнен |
| Cancel/retry/network interruption | `BLOCKED` | negative automation PASS; реальный signed-candidate flow не выполнен |
| Offline/no-external-egress observation | `NOT RUN` | source/policy scan PASS, наблюдаемый offline run отсутствует |

## Итоговый verdict

`BLOCKED`.

Причины: production release-подпись подтверждена, но artifact собран из dirty worktree;
не закрыты полная Android/browser OS-матрица и наблюдаемый offline egress.
Известных unresolved Critical/High дефектов в выполненных
автоматических gates нет, но непроверенные обязательные ячейки не преобразуются в
`PASS`.

Инструкции по безопасной подписи находятся в `docs/release-signing.md`, а полный
чеклист — в `docs/release-checklist.md`.
