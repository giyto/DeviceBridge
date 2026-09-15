# Server lifecycle verification

## Baseline before implementation

- Date: 2026-09-14
- Git commit: `27a4555f753f1f54fb665d8d5ec5395266386748`
- Web: `npm.cmd test` — 7 test files, 26 tests passed.
- Android unit: `.\gradlew.bat :app:testDebugUnitTest` — 19 suites, 39 tests, 0 failures.
- Android build: `:app:assembleDebug` and `:app:assembleRelease` — successful.
- Android instrumented: `.\gradlew.bat :app:connectedDebugAndroidTest --rerun-tasks` — 18 tests passed on `Pixel_8_API_37_1_DeviceBridge`; Android reports SDK 37.

### APK baseline

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| `app-debug.apk` | 36,916,628 | `C2902EB3AE6E5DBAD1A747D7E801AFD5D67906BDC3960D258A533ECCFA568AAB` |
| `app-release-unsigned.apk` | 23,042,273 | `CADFFBCB8F5B9B77BA5607EA8E3EBBDF44D4A4223BAE7BE698E3B34C214F0181` |

API 29 and the complete API 37.1 permission/lifecycle matrix are acceptance checks in tasks 7.1–7.4. Physical Wi-Fi and hotspot verification are acceptance checks in tasks 7.5–7.6.

## Implementation results

### Toolchain and dependency injection

- Hilt `2.59.2` and KSP `2.3.6` are pinned for AGP `9.3.2` with built-in Kotlin `2.2.10`.
- Java source and target compatibility are `17`.
- `.\gradlew.bat help :app:compileDebugKotlin :app:compileReleaseKotlin --rerun-tasks` — successful.
- Version choice follows the official AGP 9 migration guidance; KSP and Hilt are not downgraded below its supported minimums.
- Hilt graph contract followed RED → GREEN; debug tests and release graph compilation are successful with `DeviceBridgeApplication`, Android entry points and the singleton application coroutine scope.
- Production manifest permissions and the private `connectedDevice` service contract followed RED → GREEN.
- `ServerManifestInstrumentedTest` passed on Pixel_4/API 29 and Pixel_8/API 37.1; no `BOOT_COMPLETED` handler is registered.

### Foreground service and notification

- Date: 2026-09-15.
- Notification model and service source contracts followed RED → GREEN; the low-priority channel, lifecycle labels, endpoint, zero-browser/no-transfer state and immutable stop action are covered.
- Stop timeout has a RED → GREEN coordinator test and produces the recoverable `StopTimedOut` state while preserving an explicit retry path.
- Android Lint found and then verified the fix for the API 31-only `ForegroundServiceStartNotAllowedException`; the type check is now behind a guarded `@RequiresApi(31)` boundary and minSdk 29 remains supported.
- `ServerForegroundServiceLifecycleTest` passed on Pixel_4/API 29 and Pixel_8/API 37.1. It verifies the foreground notification, one listening socket, Activity background/recreation, unchanged generation/endpoint/start timestamp, idempotent repeated start, repeated rapid stop commands and stop through the real notification `PendingIntent` with notification/port cleanup.

## System acceptance matrix

### API 29 AVD

- Date: 2026-09-15; AVD: `Pixel_4`; serial: `emulator-5554`; reported SDK: `29`.
- `ServerProductionCyclesInstrumentedTest` and `ServerForegroundServiceLifecycleTest` passed: 2 tests, 0 failures.
- The production service completed 20 consecutive start → root/web-manifest → stop cycles. Every root and manifest response was HTTP 200 and every previously bound port was closed after stop.
- A real start from the Compose button reached `Running` and published `http://10.0.2.16:43655`; the value was read from the UI rather than invented by the test.
- `adb forward tcp:18787 tcp:43655` with the published authority in `Host` returned HTTP 200 for `/` and `/web-manifest.json`. The root contained `DeviceBridge`; manifest was `{"protocolVersion":1,"webAssetVersion":"sha256-2de9b00317ec0e35"}`; `/api/v1/status` returned 404.
- The Compose stop button returned the UI to `Сервер остановлен`; the old forwarded endpoint was no longer reachable. The foreground lifecycle test separately verified Activity background/recreation and the real notification `PendingIntent` stop action with socket and notification cleanup.

### API 37.1 AVD

- Date: 2026-09-15; AVD: `Pixel_8_API_37_1_DeviceBridge`; serial: `emulator-5554`; reported SDK: `37`.
- `ServerProductionCyclesInstrumentedTest` and `ServerForegroundServiceLifecycleTest` passed: 2 tests, 0 failures. The production runtime completed 20 start/route/stop cycles and the foreground notification stop contract passed on API 37.1.
- Fresh permission denial snapshot: `Сервер остановлен`, no endpoint, `Разрешите доступ к локальной сети`, and `Повторить запрос`. The retry action displayed the system LAN permission dialog again.
- LAN grant plus notification denial snapshot: `Сервер запущен`, actual endpoint `http://10.0.2.16:46763`, live uptime, `Уведомления отключены`, and a working stop action. Notification denial did not block the socket or crash the app.
- Notification grant snapshot: `Сервер запущен`, actual endpoint `http://10.0.2.16:35987`, no warning card, and an ongoing `devicebridge_server` notification with the `Остановить` action.
- Runtime LAN revocation snapshot: the published endpoint returned HTTP 200 before revocation; Android then terminated the application process, removed the foreground service, and the old endpoint returned no response (`000`). No `AndroidRuntime` fatal exception was recorded.
- The API 37.1 process-kill behavior discovered during acceptance was covered by `ServerSessionRecoveryTest` and a small persisted session journal. A cold relaunch after revocation now shows `Нужен повторный запуск`, `Нет разрешения на доступ к локальной сети`, and `Повторить запрос`, while exposing no endpoint.
- Regranting LAN permission left the recoverable error unchanged and did not recreate the service or socket. Only the explicit retry action started a new session, which published a new actual endpoint `http://10.0.2.16:44353`.

### Forced process termination

- API 37.1: a real UI-started session published `http://10.0.2.16:34869` and returned HTTP 200 through `adb forward`. `am force-stop ru.hznik.devicebridge` removed the process, service, active notification and listener; the old endpoint returned `000`. A cold relaunch showed only `Сервер остановлен` and `Запустить сервер`, with no endpoint and no automatic restart.
- API 29: the same scenario published `http://10.0.2.16:37677` and returned HTTP 200 before forced termination. The process, service, notification and listener were absent afterward, the old endpoint returned `000`, and the cold relaunch remained honestly `Stopped` without a published URL.
- The persisted journal records only that a user-started session was active; it never persists an endpoint and never restarts a service. It is consumed on the next process creation so ordinary process termination remains `Stopped`, while an API 37.1 permission-revocation kill can be explained as a recoverable permission error.

### Final APK composition

| Artifact | Baseline bytes | Current bytes | Delta bytes | Current SHA-256 |
| --- | ---: | ---: | ---: | --- |
| `app-debug.apk` | 36,916,628 | 37,895,777 | +979,149 | `CC564715A4F447F3C1E0DB506A269CEF9237DE7C2AB33520E9E61CBD47E777A3` |
| `app-release-unsigned.apk` | 23,042,273 | 29,277,523 | +6,235,250 | `D55DF3EC0ECC28F27D2BEEA7622F5E4AB75F0209769B26850B18C926B63B3DAD` |

- Both APKs were rebuilt on 2026-09-15 before measurement. The larger release delta is expected because Ktor/CIO moved from the debug-only graph into production for the local server lifecycle.
- SDK APK Analyzer (`D:\Android\Sdk\cmdline-tools\latest\bin\apkanalyzer.bat`) confirmed defined release packages `io.ktor.server.cio`, `io.ktor.network.sockets`, `ru.hznik.devicebridge.data.server`, `ru.hznik.devicebridge.server`, and `ru.hznik.devicebridge.web`, including `KtorServerRuntimeFactory`.
- The same release DEX scan returned zero matches for `ru.hznik.devicebridge.diagnostics`, `DiagnosticsActivity`, and `DiagnosticToken`; debug diagnostics and bearer-token code remain outside the release artifact.

### Architecture audit

- `settings.gradle.kts` includes only `:app`. The `web` directory is the source of the UI embedded into that APK; there is no desktop companion, external backend module or second deployable application.
- Static import audit returned `domainForbiddenImports=0`: the domain model, repository contracts and use cases import neither Android/AndroidX nor Ktor.
- Static dependency audit returned `viewModelServiceOrKtorRefs=0`: `HomeViewModel` depends on domain use cases and permission abstractions, and does not reference the foreground service, its command gateway or Ktor.
- Hilt remains the composition root: `DeviceBridgeApplication` is `@HiltAndroidApp`, Activity/service are Android entry points, and application-scope bindings are installed in `SingletonComponent`.
- Production-source audit returned zero DataStore and boot-autostart references. There is no boot receiver, pairing/session-token implementation or transfer route. Text and file actions remain disabled until the next pairing change.
- The process-recovery journal is a private, minimal SharedPreferences boolean. It stores neither endpoint nor session credentials and can only explain an interrupted session; it cannot restore or autostart the server.

### Network transition acceptance

- On API 29, disabling Wi-Fi while the real server exposed `http://10.0.2.16:34661` changed the old forwarded endpoint from HTTP 200 to no response (`000`), removed the foreground service and displayed `Нужен повторный запуск` / `Соединение с локальной сетью потеряно.`
- Re-enabling Wi-Fi restored `wlan0` with `10.0.2.16`, but the UI remained in the recoverable error and no service/socket restarted automatically.
- Deterministic coordinator tests cover disappearance of the selected address and fingerprint/interface change as `AddressChanged`, including listener cleanup and no automatic restart. The physical-device Wi-Fi-to-hotspot transition additionally verified the real OS-level IPv4 replacement that the production Google Play AVD cannot simulate (`adbd cannot run as root`).
- On the physical API 35 device, the Wi-Fi endpoint `http://192.168.0.90:39741` returned HTTP 200 before Wi-Fi was disabled. After the real radio transition the URL stopped responding (`000`), the foreground service disappeared and the UI showed the recoverable network-loss error. Restoring Wi-Fi returned the same DHCP address but did not restart the service; an explicit retry created the new listener `http://192.168.0.90:37421`. Switching to the phone hotspot replaced the device IPv4 with `10.211.20.14`; the old Wi-Fi endpoint remained closed and an explicit start published `http://10.211.20.14:37935` without automatic restart.

### Physical device status

- The physical device `22081212UG` is visible to ADB as serial `905640a3`, reports API 35 and successfully installed the current debug APK after the user allowed USB installation.
- On Wi-Fi `192.168.0.0/24`, the application published `http://192.168.0.90:39741` without `adb forward`. Direct requests from the computer returned HTTP 200 for `/`, `/web-manifest.json`, the versioned CSS and the versioned JavaScript; `/api/v1/status` and `/diagnostics/health` returned 404. The root contained no external HTTP(S) references.
- Installed Chrome and Edge both loaded that physical-device URL in isolated headless profiles with exit code 0 and rendered DOM containing `DeviceBridge`. The user then used the in-app copy action and pasted `http://192.168.0.90:37421`; direct root and manifest checks against that newly started endpoint also returned HTTP 200.
- With the computer connected directly to the phone hotspot, the user confirmed that `http://10.211.20.14:37935` loaded DeviceBridge in both Chrome and Edge without Internet access or `adb forward`. Pressing the application stop action made the page unavailable on refresh, confirming hotspot reachability and listener cleanup.

### Final automated verification

- `npm.cmd test` — 7 files, 26 tests, 0 failures; its pretest Vite build succeeded.
- `npm.cmd run build` — TypeScript check and Vite production build succeeded; 8 modules transformed.
- `.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease` — successful. JUnit XML contains 40 suites / 101 tests / 0 failures / 0 errors / 0 skipped. Lint contains 0 errors and 36 warnings; both APKs were produced.
- `$env:ANDROID_SERIAL='emulator-5554'; .\gradlew.bat :app:connectedDebugAndroidTest` — 27/27 passed on `Pixel_4` / API 29 after the final fixes.
- The same serial-pinned command — 27/27 passed on `Pixel_8_API_37_1_DeviceBridge` / API 37.1 after the final fixes.
- The first complete API 29 run exposed a start/stop race in which delayed cleanup could tear down a newer foreground-service start. The service now refreshes foreground state for every start and uses `startId` plus `stopSelfResult` so stale cleanup cannot stop a newer command; the regression contract, isolated 20-cycle test, combined lifecycle tests and complete matrix all pass.
- A second stop-command race was covered explicitly: two rapid STOP intents now share the active cleanup and retain the newest pending `startId`, so the service always removes its notification, closes the listener and terminates after cleanup on both API levels.
- The first complete API 37.1 run exposed a platform restriction in reading the real clipboard from a headless test process. The UI test now injects a `LocalClipboard` recording fake and verifies the exact `ClipEntry`; production still uses Compose's Android clipboard adapter.

### Specification alignment

- `docs/technical-specification.md` version 2.0 and `openspec/roadmap.md` both define one installable Android application whose embedded browser assets are served over the local LAN. The implementation keeps that boundary and introduces no desktop companion, cloud service, account or rented infrastructure.
- The implemented scope matches roadmap change 4 and FR-01/FR-08/related FR-09 clauses: explicit start, actual IPv4 plus dynamically bound port, monotonic uptime, UI/notification stop, `connectedDevice` foreground service, no boot/process/network autostart, API 37 LAN permission, non-blocking notification permission and fail-closed permission/network handling.
- The production route set matches both delta specs: root, bundled assets and public `web-manifest.json` are available; debug diagnostics stay debug-only; unfinished `/api/v1/*` routes return 404. Host/origin checks and versioned assets remain in place.
- Android UI matches the app-shell delta: lifecycle states are derived from one process-wide coordinator, the endpoint exists only in `Running`, copy has an accessible label, and text/file actions remain disabled before pairing.
- Proposal, design, tasks and both delta specs consistently keep pairing/session tokens, bidirectional text, file transfer, Room/DataStore history/settings and trusted browsers in later roadmap changes 5–8. The one-time code described by the final MVP TЗ is therefore intentionally absent at this stage, not silently replaced by the old debug bearer token.
- Verification did not expand the scope: the only new persistence is a private boolean journal needed to explain an API 37 permission-revocation process kill, and it cannot persist or restore a server endpoint/session.
