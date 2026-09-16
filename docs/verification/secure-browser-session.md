# Secure browser session verification

## Baseline before implementation

- Date: 2026-09-15.
- Planning commit: `b61a659` (`spec: plan secure browser sessions`).
- `npm.cmd test` from `web/` — successful: Vite production prebuild completed, 7 test files and 26 tests passed.
- `.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease` — successful: 112 actionable tasks, 11 executed and 101 up-to-date.
- JUnit XML baseline: 40 suites, 101 tests, 0 failures, 0 errors, 0 skipped.
- Android lint baseline: 0 errors and 36 warnings.
- Debug APK: 37,776,467 bytes; SHA-256 `571BA5281F0DF983DA1952701C143C0632919A1C771E1490F3357CE1C062CF4F`.
- Release unsigned APK: 29,277,523 bytes; SHA-256 `E15A94A8014D7D47D5975E35C26ED0BB25F01D6FF1222FEEFDB60B9DCE305456`.
- Connected baseline was attempted on physical `22081212UG`, serial `905640a3`, API 35. Gradle built both APKs, but Android rejected installation of the test APK with `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`; therefore 0 connected tests executed in this baseline run. This is a device installation-policy prerequisite and will be retried in the acceptance matrix after USB installation is allowed.
- The completed previous change retains recorded green connected baselines of 27/27 on API 29 and 27/27 on API 37.1 in `docs/verification/server-lifecycle.md`.

## Implementation verification

Results will be appended as each OpenSpec task completes. Secrets, pairing codes and raw session tokens MUST NOT be copied into this report.

### Session protocol and dependency baseline

- Added an exact production route policy for public, bearer-protected and auth-first WebSocket endpoints; unfinished diagnostics/transfer/trusted-browser routes remain outside the production surface.
- Added strict, versioned HTTP serialization contracts and a stable error envelope with field and 4 KiB payload limits.
- Added a versioned WebSocket authentication envelope and validation for unsupported versions and duplicate control message identifiers.
- Moved Ktor server WebSockets into the production dependency set while retaining diagnostic routes in the debug source set only.
- RED checks failed for each missing contract/dependency as expected; the subsequent targeted protocol and release-isolation test run passed.
- `:app:compileDebugKotlin` and `:app:compileReleaseKotlin` both completed successfully with no duplicate-class failures.

### Domain browser-session contracts

- Added immutable pairing-code, pending-request, active-session and aggregate state models with typed errors and typed generation/challenge/request/session identifiers.
- Added a pure reducer covering inactive, ready, pending, connected, blocked and error phases while rejecting stale generations and invalid decisions.
- Added a token-free `BrowserSessionRepository` contract and repository-only observe/approve/deny/revoke use cases.
- Added architecture checks preventing Android, Compose, Ktor and injection imports in the production domain source set and server/crypto/storage ownership in `HomeViewModel`.
- Targeted domain, reducer, repository, use-case, identifier and architecture tests completed successfully.

### Security primitives and resource bounds

- Added a `SecureRandom` adapter using bounded `nextInt` for zero-padded six-digit codes and 32 random bytes encoded as unpadded base64url session tokens.
- Added SHA-256-only session credentials with generation binding and `MessageDigest.isEqual` verification; raw token strings are not retained in credential fields.
- Added monotonic five-minute pairing-code lifetime, automatic rotation on expiry and 60-second pending-request lifetime without wall-clock reads.
- Added an independent per-IPv4 five-attempt limiter with a 60-second block and bounded source records.
- Added bounded expiring registries for challenges and pending requests, plus normalized browser-label/IPv4 and 4 KiB JSON input checks.
- All targeted security, TTL, rate-limit, hostile-input memory-bound and metadata tests completed successfully.

### Process-wide session coordinator

- Added a mutex-serialized coordinator with one active opaque generation handle and a process-wide `StateFlow`.
- Added bounded opaque challenges, exact metadata/source binding and confirm validation against code TTL and per-IPv4 rate limits.
- Added 60-second suspend confirmation backed by bounded `CompletableDeferred` entries; only explicit Android approval creates a token-bearing response.
- Added concurrent request isolation and idempotent approve/deny behavior.
- Added SHA-256 bearer lookup, token-free session metadata, individual revoke and transport-independent connection closure.
- Added generation cleanup for code, challenges, pending decisions, sessions, credentials, rotation jobs and connections; stale handles cannot mutate a newer generation.
- The full coordinator activation/challenge/validation/decision/concurrency/registry/cleanup test set completed successfully.

### Protected Ktor session surface

- Added exact Host and same-origin guards for HTTP and WebSocket, mandatory JSON media type, streamed 4 KiB body enforcement and no permissive cross-origin headers.
- Added versioned challenge and confirm routes with stable JSON error envelopes and long-poll approve/deny/timeout behavior.
- Added strict single-value Bearer parsing, digest-backed authorization, minimal protected status and own-session deletion.
- Added auth-first WebSocket with bounded auth timeout/frame size, version/message validation, no pre-auth events and revoke-bound closure.
- Verified diagnostics, trusted-browser and unfinished text/file/transfer endpoints remain 404 even when a valid session token is presented.
- All targeted route/security/WebSocket tests passed; debug and release Kotlin compilation completed successfully.

### Server lifecycle and foreground notification

- The production CIO listener is started before its browser-session generation is activated; bind/start failure closes the unpublished generation and listener without exposing a pairing code.
- Normal stop, network loss, IPv4/fingerprint change and local-network permission revocation close the session generation before stopping CIO, invalidating pending confirmations, connections and bearer credentials.
- A later explicit start activates a fresh generation; stale generation handles and tokens cannot authorize against it.
- The foreground notification now observes the same process-wide session state and shows the current active-browser count while never including the pairing code, bearer token or raw credential.
- Targeted runtime, ordered-stop, network-change, permission-revocation and notification tests completed successfully.

### Android pairing presentation

- `HomeViewModel` now combines lifecycle and process-wide browser-session flows and maps pairing code/countdown, pending requests and active-session metadata into token-free UI state.
- Approve, deny and revoke actions carry typed identifiers, delegate only through domain use cases and suppress duplicate in-flight actions.
- The Compose screen includes pairing instructions, pending-browser approval cards and individually revocable active-browser cards while transfer controls remain disabled.
- Eight HomeViewModel tests pass. The eight HomeScreen Compose tests passed 8/8 on the headless API 29 `Pixel_4` AVD and 8/8 on the headless API 37.1 `Pixel_8_API_37_1_DeviceBridge` AVD, including dark theme and 2.0 font scale. The separate physical-device attempt remains blocked by its installer policy (`INSTALL_FAILED_USER_RESTRICTED`).

### Bundled web pairing client

- Added a typed same-origin challenge/confirm/status/delete client with stable server-error mapping and Bearer credentials only in protected request headers.
- Added a controller covering checking, ready, submitting, awaiting phone approval, connected, blocked, expired, denied, session-lost and bounded offline-retry states.
- Raw session credentials are held only in the current tab's `sessionStorage`; they are cleared after explicit revoke, 401, incompatible protocol and WebSocket auth loss, and never enter URLs, logs, cookies or rendered state.
- Added an auth-first same-origin WebSocket client with the token in its first frame, policy-close handling and bounded 1/2/4-second reconnect.
- Added a keyboard-accessible six-digit form, polite live status, visible focus, safe `textContent` rendering and honestly disabled transfer controls.
- `npm.cmd test` passed 39/39 tests, TypeScript typecheck passed, and two production builds produced byte-identical SHA-256 results for every generated asset.
- The current bundled assets were packaged successfully into both debug and release APK builds; the final Android unit suite passed 192/192 tests.

### Final automated acceptance

- Process-recovery tests simulate an interrupted previous process and confirm that it never auto-restores the listener. The lifecycle journal has only running-marker operations, cannot store pairing/browser credentials and is explicitly excluded from cloud backup and device transfer. Activity/ViewModel recreation reads the same live process-wide session state.
- The focused server/session integration matrix passed 50/50 tests across 20 suites. It covers correct, wrong and expired codes; the fifth-attempt block; approve, deny and timeout; concurrent/duplicate decisions; missing/bad/revoked/old-generation Bearer credentials; auth-first WebSocket and revoke closure; and release-only negative routes.
- Final web verification: 11 suites and 39 tests passed, strict TypeScript typecheck passed, and two consecutive builds produced identical hashes for all six generated files.
- Final Android verification after the refresh regression fix: 72 unit suites and 192 tests passed with 0 failures, 0 errors and 0 skipped; `lintDebug` passed with 0 errors and the unchanged 36 baseline warnings; debug and release APK assembly passed.
- Debug APK: 38,142,094 bytes; SHA-256 `85388F023F3B213ABE4776A4E8CE60A30677B12286857AE1339829AC1A532232`.
- Release unsigned APK: 29,609,599 bytes; SHA-256 `57001B54A9BFBC62EA3440D5BF9B394E69AC9B702AB353981E89B7E0E0EE5560`.
- Full Android instrumentation passed 30/30 tests on API 29 and 30/30 tests on API 37.1, including Activity restoration, foreground service lifecycle, production server cycles, Compose UI and embedded web assets.
- Security review found no pasted/raw credential fixture in the repository scan or JUnit XML, no production logging API for session secrets, no credential persistence in Android storage, no token in URL/cookie/localStorage, and no code/token in notification models. The public manifest remains exactly version plus web asset hash.
- The implementation retains the documented LAN HTTP/WebSocket limitation: session authorization prevents unauthorized API use but does not encrypt traffic from another participant in the same network, so the UI explicitly requires a trusted private network.
- Scope comparison against ТЗ 2.0, roadmap, proposal, design and all four delta specs confirms one Android application, browser-as-client, Clean Architecture/MVVM/SOLID boundaries, and no trusted-browser or text/file transfer implementation in this change.
- `openspec validate add-secure-browser-session --strict` completed successfully.

### API 29 manual acceptance

- The full 30-test instrumentation suite passed on the `Pixel_4` API 29 AVD before manual acceptance.
- A real ADB-forward HTTP flow exposed that the transport reports loopback clients as `localhost`/IPv6 loopback. A RED regression test reproduced the rejection, after which the transport boundary was changed to canonicalize only loopback aliases to `127.0.0.1`; unsupported non-IPv4 addresses remain rejected.
- Pairing through the forwarded production endpoint completed with an opaque challenge, phone-side approve and a protected status response reporting one connected browser. The raw credential and pairing code were not copied into this report.
- Phone-side deny returned HTTP 403 with stable `DENIED`; individual revoke removed the Android session immediately, changed the notification count from one browser to zero and made the old token return HTTP 401 with `UNAUTHORIZED`.
- Activity recreation was exercised by a configuration rotation while the server and session remained live. The recreated UI retained the same active browser and protected status remained connected.
- A raw RFC 6455 handshake against the forwarded production endpoint returned HTTP 101. After the auth-first frame, the server emitted `session.authenticated`; phone-side revoke then emitted close opcode 8 with policy code 1008 and reason `Session revoked`.
- Stopping the server made the forwarded manifest endpoint unreachable, displayed the stopped Android state and removed DeviceBridge from the active notification list.

### API 37.1 permission and recovery acceptance

- The full 30-test instrumentation suite passed on the `Pixel_8_API_37_1_DeviceBridge` AVD before the permission matrix.
- Denying both Local Network and notification permissions kept the server stopped, showed a Local Network rationale/retry action and separately explained that notifications were disabled.
- Granting Local Network while denying notifications allowed an explicit server start and displayed the running endpoint plus an honest no-notification warning. Granting notifications in the later run produced the expected active foreground notification.
- Pairing and protected status succeeded with an active browser before runtime Local Network revocation. Revoking that permission made the forwarded endpoint unreachable immediately; Android terminated the application process as part of permission revocation and no crash loop occurred.
- Launching after revocation did not restore the listener, pairing code or browser session. After granting the permissions, an additional force-stop/start also opened in the stopped state with no pairing code.
- Only an explicit start created a fresh server generation. The credential issued before permission revocation returned HTTP 401 with `UNAUTHORIZED` against the new endpoint.
- The post-matrix regression run also passed all 39 web tests, strict TypeScript typecheck and `openspec validate add-secure-browser-session --strict`.

### Browser refresh regression

- Physical Chrome/Edge acceptance first exposed that refreshing an authorized tab could lose the connected UI. One server-side cause was the protected status guard requiring `Origin` on `GET /api/v1/status`; the Fetch Standard does not require `Origin` on a same-origin GET.
- A RED route regression reproduced HTTP 403 for a valid bearer without `Origin`. The guard now allows missing `Origin` only for the protected status GET while still requiring the published Host and a valid bearer token; any supplied Origin is checked exactly. An explicit regression keeps originless `DELETE /api/v1/session` forbidden.
- Follow-up physical acceptance showed a second failure mode: Android retained the approved session while the browser returned to pairing. The web controller treated every event-channel auth/policy close as proof of revocation and cleared `sessionStorage` without checking the protected HTTP status.
- A RED controller regression reproduced the missing revalidation. Event-channel loss now triggers a protected status check: a still-valid bearer reconnects without showing the code form, only a confirmed HTTP 401 clears the tab token, and transient transport errors keep the token for bounded recovery.
- After this fix all 11 web suites passed 40/40 tests, strict TypeScript typecheck passed, production web assets rebuilt successfully and `:app:testDebugUnitTest` completed successfully.

### Physical Wi-Fi and hotspot acceptance

- On the physical Android host, Chrome and Edge completed code entry plus explicit phone approval directly through the published Wi-Fi endpoint without `adb forward`.
- Refreshing the same authorized tab repeatedly restored the protected status without another code, while Android retained exactly one active session. Revoking one browser disconnected only that browser; the other remained connected, and the revoked browser completed a fresh pairing successfully.
- The same pairing, revoke and stop flow passed while the computer was connected to the phone hotspot with Internet access disabled. The browser loaded the bundled interface and completed authorization using only the new local hotspot endpoint.
- The hotspot run exposed the new local IPv4, made the previous origin/session unusable, performed no external request, and showed consistent connected, revoked and stopped states in both Android and browser interfaces.
- After an explicit server restart, the previous browser credential did not restore authorization and a new pairing code plus phone approval were required.
