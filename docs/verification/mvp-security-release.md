# Проверка безопасности и release readiness MVP

## Candidate identity

This report starts the evidence chain for OpenSpec change `prepare-mvp-security-release`.

| Field | Current value |
| --- | --- |
| Captured | 2026-09-22, полный повторный прогон всех gates и ручной матрицы (предыдущие прогоны: 2026-09-20 и утро 2026-09-22) |
| Commit | `b885044ff037f17304bc129d39b111139d8b2ca9` |
| Android application ID | `ru.hznik.devicebridge` |
| versionCode / versionName | `3` / `1.0.2` |
| minSdk / targetSdk / compileSdk | 29 / 37 / 37 |
| Build types | `debug`, `release` |
| Release signing baseline | none; `:app:signingReport` reports `Config: none` |
| Current release signing | external RSA-4096 release key; one signer; APK Signature Scheme v2 verified |
| Current signed artifact | `app-release.apk`; SHA-256 `9195f45eec5052f3aa9631e9266b29734a1f4198e09270928706a52a4e04826f` (пересобран из того же commit; предыдущая сборка `7f41cf8f…e64b` заменена) |
| Worktree at build | clean (`gitWorktreeClean: true`); the previous `BLOCKED_DIRTY` blocker is resolved |
| Previous candidate | `2` / `1.0.1`, commit `e5115a7`, SHA-256 `cbe62b03…e7d8`; superseded because it predates the committed UI changes |
| Bundled web manifest | protocol `1`, asset version `sha256-30e0aee2da79e11c` |
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

## Полный повторный прогон 2026-09-22, вечер (candidate `3/1.0.2`, SHA-256 `9195f45e…826f`)

Целевая матрица изменена решением владельца продукта: Windows 10 исключена, browser acceptance
выполняется в Chrome и Edge на Windows 11 (delta specs `browser-web-interface`,
`error-recovery-and-accessibility`, ТЗ FR-02/NFR-05/критерий 13). Все проверки ниже выполнены
заново; результаты утреннего прогона сохранены в следующем разделе как история.

### Автоматические gates

| Gate | Результат |
| --- | --- |
| Android JVM/Ktor | PASS: 487/487 в 142 suites, 0 failures/errors/skips (`--rerun-tasks`, 97 tasks, 5m27s) |
| Lint | PASS: 0 errors, 66 warnings |
| `assembleRelease`, `verifyReleasePolicy`, `generateReleaseMetadata` | PASS; metadata: commit `b885044`, `gitWorktreeClean: true`, `3/1.0.2`, `webAssetVersion=sha256-30e0aee2da79e11c` |
| Release signing | PASS: внешние параметры из Gradle user home; один signer, v2, `apksigner verify` PASS; сертификат совпадает с `1/1.0` и `2/1.0.1` |
| Compose/instrumentation API 37.1 | INSTR_37 |
| Compose/instrumentation API 29 | INSTR_29 |
| Web typecheck / unit / build | PASS: typecheck; 212/212 в 39 files; production build |
| Visual | PASS: 51 applicable, 9 configuration skips |
| Chrome/Edge compatibility | PASS: 6/6 |
| File transfer | PASS: 14/14; 500 MiB digest `47c2e1d3…6290` в Chrome и Edge, heap growth 0 |
| Release policy | PASS: 2/2 fixtures, 8 roots |
| Пересборка web assets | не изменила ни одного tracked файла в `app/` и `web/` |

### APK inspection

- `debuggable` отсутствует (false); permissions без изменений; native libs для четырёх ABI.
- Шесть `assets/web/*` entries побайтно совпадают с production build output.
- DEX: совпадений `/diagnostics`, diagnostics classes — `0`.
- Private keys, keystore files, внешние URL, analytics/CDN markers и service worker в APK — `0`.
  Строки `hznik` в DEX — только Hilt-идентификаторы пакета `ru.hznik.devicebridge`.

### Реальная матрица signed candidate

Браузеры: Chrome `153.0.8010.53`, Edge `153.0.4234.32`, Windows 11 host. Android: AVD API 37.1
(`Pixel_8_API_37_1_DeviceBridge`) и API 29 (`Pixel_4`). Браузер обращается к published Host
через `--host-resolver-rules` и `adb forward`; телефон управляется только через системный UI.

| Проверка | API 37.1 | API 29 |
| --- | --- | --- |
| Update `2/1.0.1` → `3/1.0.2` | PASS: имя, retention `45`, limit `900 MiB`, SAF, trusted Chrome, история сохранены; trusted reconnect без кода | PASS: те же данные, trusted Edge; trusted reconnect без кода |
| Clean install, routes | PASS: `/` `200`, чужой Host `403`, `/diagnostics/health` `404`, `/api/v1/status` без сессии `401`, чужой Origin `403`, CORS preflight `405` без `Access-Control-*` | PASS: те же ответы |
| Local-network permission | PASS: deny → listener не открыт, recovery card → retry → allow | N/A |
| Notification и lifecycle | PASS: stop из notification, stop из приложения, restart, force-stop без автозапуска; listener/foreground service закрыты; crash buffer пуст | PASS: то же |
| Pairing | PASS: keyboard-only ввод кода, «Разрешить и запомнить» (Chrome), «Один раз» (Edge), «Отклонить» → `denied`, API `401` | PASS: Chrome keyboard-only + remember, Edge one-time |
| Layout | PASS: Chrome и Edge, ready и connected × 360/768/1920 × light/dark: без overflow и clipped controls, warning видим | PASS: Chrome, те же 12 комбинаций |
| Keyboard/accessibility | PASS: порядок Tab = визуальный, `:focus-visible` на всех остановках, отправка текста клавиатурой | PASS |
| Text/link browser → phone | PASS: `Доставлено`; `<b>` показан как текст в браузере и на телефоне | PASS |
| Text phone → browser (Share Target text) | PASS с находкой F3 | PASS с находкой F3 |
| Два файла browser → phone | PASS: SHA-256 совпадают (`53c046b7…5182`, `5f9bdb66…4b76`) | PASS (Chrome и Edge) |
| Два файла phone → browser | PASS: SHA-256 совпадают (`e47396e3…ae0f`, `823cdc24…3d3d`) | PASS (Chrome и Edge) |
| 500 MiB browser → phone | PASS: 36 s, SHA-256 `a08a9225…0170`, JS heap ≤ 4 MB, app PSS 75–93 MB | PASS: 73 s, тот же SHA-256, heap ≤ 4 MB, PSS 63–81 MB |
| 500 MiB phone → browser | PASS: 41 s, тот же SHA-256, heap ≤ 4 MB, PSS 64–110 MB | PASS: 64 s, тот же SHA-256, heap ≤ 3 MB, PSS 66–79 MB |
| Cancel + retry | PASS с находкой F2: cancel в браузере на 30%, частичный файл удалён, retry той же передачи завершён с верным SHA-256 | PASS с находкой F2: cancel на телефоне на 20%, частичный файл удалён, retry завершён с верным SHA-256 |
| Network interruption | PASS: Wi-Fi off на 25% в обе стороны → browser `offline`, card `CANCELLED`, download отменён, частичного файла нет, listener закрыт, история «Отменено»; телефон требует «Запустить снова» | PASS: browser → phone, тот же результат |
| Revoke | PASS: «Отключить» → `sessionLost`; отзыв trusted → `sessionLost`, после reload нужен код, credential удалён из browser storage | PASS: то же |
| Share Target файлов | FAIL: F1 | FAIL: F1 |
| Внешние запросы браузера | `0` во всех сессиях | `0` |
| Egress Android app | PASS (наблюдение): у UID приложения нет ни одной записи трафика на cellular/Wi-Fi/eth в `netstats`; tcpdump эмулятора содержит только системный трафик Google Play Services и connectivity check | PASS (наблюдение): то же; tcpdump содержит только системный трафик Google-приложений образа |

### Findings

| ID | Severity | Описание | Evidence | Статус |
| --- | --- | --- | --- | --- |
| F1 | High | Share Target отклоняет любой файл на чистой установке («Недоступных или слишком больших файлов: 1», даже для 46 байт). `AndroidFileSelectionPreparer.prepare` при `stageTemporarySources = true` проверяет `File.getUsableSpace()` каталога `no_backup/file-sources` до его создания; для несуществующего пути это `0`. Picker «Добавить файлы» не затронут. Нарушает критерий ТЗ 9 | Воспроизведено через системное приложение «Файлы» → Share на API 29 и API 37.1 release. На debug-сборке без каталога — отказ, после `mkdir no_backup/file-sources` тот же share даёт «Выбрано: 1». Утренний прогон проверял только text share | Open, blocks release |
| F2 | Medium | Terminal status browser → phone transfer расходится после cancel: cancel в браузере даёт `CANCELLED` → `TRANSFERRING` → `FAILED` («Передача ещё не подтверждена на телефоне»); cancel на телефоне оставляет браузер в `TRANSFERRING` около 40 s, затем `FAILED` («Сеть прервала передачу файла»). Телефон и история показывают «Отменено». Вероятная причина: `fileTransferController.receiveProgress` принимает запоздавший progress для завершённой передачи и перезапускает upload | Ложного `COMPLETED` нет, частичный файл удалён, retry работает | Open |
| F3 | Low | Live `text.received` несёт статус отправителя на момент отправки (`SENDING`), поэтому полученное браузером сообщение показано как «Отправляется» до reload; snapshot после reload показывает `DELIVERED` | API 29 и 37.1, Chrome и Edge | Open |
| F4 | Low | Скорость передачи в браузере большую часть времени показана как «0 Б/с» | 500 MiB runs | Open |
| F5 | Low | Главный экран Android показывает входящие файлы, ожидающие подтверждения, как «Передаётся · 0%» | API 37.1 | Open |
| F6 | Low | После успешного pairing фокус переходит на `BODY`, так как кнопка «Подключить браузер» скрывается | Chrome/Edge keyboard run | Open |

### Работа без Интернета

- Автоматизированный прогон шёл с подключённым к Интернету host; egress подтверждён наблюдением
  (UID netstats, tcpdump, browser request log).
- Ручная проверка в среде без Интернета выполнена владельцем продукта 2026-09-22 и принята как
  evidence задачи 5.4. Детали окружения и набор flows ручного прогона в этот отчёт не переданы.

## Утренний прогон 2026-09-22 (история, candidate `3/1.0.2`, SHA-256 `7f41cf8f…e64b`)

Все автоматические gates и Android release smoke повторены для candidate, собранного из
чистого commit `b885044`. Evidence ниже заменяет результаты для `2/1.0.1` там, где они
пересекаются; история прошлого candidate сохранена в следующих разделах.

| Gate | Результат 2026-09-22 |
| --- | --- |
| Android JVM/Ktor | PASS: 487/487 в 142 suites, 0 failures/errors/skips |
| Lint | PASS: 0 errors, 66 warnings (как у прошлого candidate) |
| `assembleRelease`, `verifyReleasePolicy`, `generateReleaseMetadata` | PASS; metadata: commit `b885044`, `gitWorktreeClean: true` |
| Signed candidate | PASS: `assembleDistributionRelease` `3/1.0.2`, previous `2`; один RSA-4096 signer, v2, `apksigner verify` PASS |
| APK inspection | PASS: `debuggable=false`; шесть `assets/web/*` entries; web/asset manifests совпадают с bundled assets; diagnostics DEX matches `0`; permissions без изменений; native libs для четырёх ABI |
| Web typecheck / unit / build / policy | PASS: 212/212 в 39 files; 38 modules; policy 2/2 fixtures, 8 roots |
| Visual | PASS: 51 applicable, 9 configuration skips. Первый запуск прерван `Target crashed` в Chrome при свободной памяти хоста < 1 GB; это environment failure, повтор без изменений кода прошёл |
| Chrome/Edge compatibility | PASS: 6/6 (Chrome `153.0.8010.50`, Edge `153.0.4234.32`, Windows 11) |
| File transfer | PASS: 14/14; 500 MiB digest `47c2e1d3068c1e31e17d7d6a2787f22ce486bc224772bbdc54fff13598306290` в Chrome и Edge |
| Instrumentation API 37.1 | PASS: 119/119 (`Pixel_8_API_37_1_DeviceBridge`) |
| Instrumentation API 29 | PASS: 119/119 (`Pixel_4`) |

Android release smoke для signed `3/1.0.2`:

- API 37.1 update `2/1.0.1` → `3/1.0.2` с тем же certificate: device name, retention `30`,
  file limit `1024 MiB`, SAF destination и история сохранены; crash buffer пуст.
- API 29 update `2/1.0.1` → `3/1.0.2`: те же настройки, SAF destination и история сохранены;
  crash buffer пуст.
- Clean install на API 37.1: local-network permission deny → recovery card → retry → allow;
  foreground notification `devicebridge_server`; `/` с published Host `200`, чужой Host `403`,
  `/diagnostics/health` `404`, неавторизованный `/api/v1/status` `401`.
- Clean install на API 29: те же route-ответы и notification; runtime local-network permission N/A.
- Реальный pairing с явным подтверждением на эмуляторе: Chrome на API 37.1, Edge на API 29
  (browser на Windows 11 host, `--host-resolver-rules` + `adb forward` сохраняют published Host).
- В обоих flows: text и link `Доставлено`; два последовательных файла browser → phone приняты
  на телефоне и сохранены через системный document picker; внешних запросов браузера `0`.
- Прерванные прогоны harness оставили transfers в состоянии `Ошибка — Браузер отключён`
  с действием `Повторить`; ложного `Завершено` не наблюдалось.
- Revoke подключённой browser session с телефона уменьшает счётчик сессий (3 → 2 → 0).
- Share Target: `ACTION_SEND text/plain` открывает draft «Текст и ссылки» без получателя
  при отсутствии подключённых браузеров.
- Lifecycle на обоих API: stop → listener закрыт (connection refused), активных notifications
  DeviceBridge `0`; restart → `200`; повторный stop → закрыт; crash buffer пуст.

Scope note: candidate `3/1.0.2` включает UI-изменения commits `4294793` (web connection panel)
и `b885044` (Android navigation, settings, theme switch). Они не относятся к scope этого change,
не меняют protocol, pairing, transfer state machine или persistence schema (добавлен только
отдельный DataStore key `theme_preference`) и должны быть оформлены отдельным OpenSpec change.

Environment note: хост 15.7 GB RAM; при одновременной работе эмулятора, IDE и браузеров
свободная память падала до 0.5 GB, что вызвало crash Chrome renderer и JVM OOM
(`hs_err` вне repository). Результаты засчитаны только из прогонов без таких сбоев.

## Текущие blockers и следующие gates

- Production release signing настроен вне repository; fail-closed Gradle gate и `apksigner verify` проходят без debug fallback.
- Resolved 2026-09-22: candidate `3/1.0.2` собран из чистого commit `b885044`; `BLOCKED_DIRTY` больше не применяется.
- Signed candidate `3/1.0.2` прошёл update, clean install и release smoke на API 29 и API 37.1.
- Update `2/1.0.1` → `3/1.0.2` с тем же release certificate прошёл на API 29 и API 37.1 без потери settings, SAF destination и history.
- Windows 10 исключена из целевой матрицы; Chrome/Edge Windows 11 acceptance выполнена вечером 2026-09-22.
- Реальные 500 MiB, cancel/retry и network interruption на signed candidate выполнены на API 29 и API 37.1.
- `BLOCKED`: F1 (High) — Share Target не принимает файлы на чистой установке.
- Работа без Интернета: ручная проверка владельцем продукта 2026-09-22, egress также подтверждён наблюдением.
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
Статусы для signed `3/1.0.2`, SHA-256 `9195f45e…826f`, по вечернему прогону 2026-09-22.

| Ячейка | Статус | Evidence / причина |
| --- | --- | --- |
| API 29 release clean install/smoke | `FAIL` | start/stop/restart/notification, routes, pairing, text/link, files в обе стороны, text Share Target, history и lifecycle PASS; file Share Target FAIL (F1) |
| API 37.1 release clean install/smoke | `FAIL` | то же плюс permission deny/retry/allow PASS; file Share Target FAIL (F1) |
| Update поверх предыдущей release | `PASS` | `2/1.0.1` → `3/1.0.2` на API 29 и API 37.1; settings, trusted browser, history и SAF destination сохранены |
| Chrome/Edge Windows 11 | `PASS` | layout, warning, pairing/approval/deny/revoke, trusted reconnect, text/file flows, cancel/retry, keyboard и отсутствие external requests; находки F2–F6 не блокируют |
| 500 MiB обе стороны / checksum | `PASS` | API 29 и API 37.1, SHA-256 `a08a9225…0170` в обе стороны; потоковая память |
| Cancel/retry/network interruption | `PASS` | ложного `COMPLETED` нет, частичные файлы удалены, retry завершён с верным SHA-256; расхождение статусов — F2 (Medium) |
| No-external-egress observation | `PASS` | browser request log, UID netstats и tcpdump на обоих API |
| Работа без Интернета | `PASS` | ручная проверка владельцем продукта 2026-09-22; автоматизированный прогон — только наблюдение egress |

## Итоговый verdict

`BLOCKED`.

Причины (2026-09-22, вечер): signed candidate `3/1.0.2` собран из чистого commit и прошёл все
автоматические gates, update, 500 MiB, cancel/retry, network interruption, browser-матрицу
Windows 11 и проверку без Интернета. Релиз блокирует F1 (High): Share Target не принимает файлы
на чистой установке — критерий ТЗ 9 не выполнен.

Находки F2–F6 не относятся к Critical/High, но должны быть оценены до публикации.

Инструкции по безопасной подписи находятся в `docs/release-signing.md`, а полный
чеклист — в `docs/release-checklist.md`.
