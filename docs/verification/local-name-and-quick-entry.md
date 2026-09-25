# Local name and quick entry verification

Change: `add-local-name-and-quick-entry`. Date: 2026-09-25.

## Automated checks

- Android unit: `.\gradlew.bat :app:testDebugUnitTest` - 674 tests, 0 failures. New or changed suites:
  - `MdnsCodecTest`: reference packets (Windows A+AAAA with name compression, legacy unicast, QU), round trips of answers, NSEC «no IPv6», probes; truncated packets, pointer loops, forward pointers, huge counts, oversized packets and over-long names are rejected without exceptions.
  - `LocalNameResponderTest` (fake transport and clock): three probes over 750 ms, suffixes `-2`…`-9`, «all taken», simultaneous probes (RFC 6762 §8.2 in both directions), own echo is no conflict, two announcements a second apart, answers with NSEC, one multicast answer per second, unicast answers to QU and legacy questions, known-answer suppression, other subnets and names ignored, a conflict while running gives the name up, goodbye once.
  - `KtorServerRuntimeFactoryTest`: the claimed name is published and accepted as Host for the page and the API; other `.local` names, a name without a port and a lost name get 403; a suffix, «all taken» and missing mDNS keep the server working by IP; secure mode serves HTTPS by name and redirects to `https://devicebridge.local:<port>`; a root from before custom names skips a name it does not cover.
  - `ServerLifecycleEndpointChangesTest`, `ServerEndpointTest`, `LocalNameNoticeTest`, `ServerNotificationModelTest`.
  - `X509ProfilesTest`, `LocalCertificateAuthorityTest`: the root permits every `.local` name and still rejects public names and IPs; a legacy root permits only `devicebridge.local`; the server certificate follows the claimed name.
  - `NetworkNameTest`, `DataStoreSettingsRepositoryTest`, `SettingsViewModelTest`: rules of «Имя в сети», lower case, fallback for a stored bad value, the restart hint, the certificate hint.
  - `EventNotificationModelTest`, `EventNotificationActionHandlerTest`: pairing buttons for API 31+ and 29-30, the unlock flag, «Разрешить» never remembers, a stale request only loses its notification.
- `:app:lintDebug`: no new findings.
- Web: `npm test` - 43 files, 286 tests (new `availabilityWaiter`, waiting and notices in `sessionController` and `shellView`, kept file draft); `npm run test:compatibility` - 10 passed in Chrome and Edge (a revoked remembered login shows «Этот телефон не узнал браузер»); `npm run test:visual` - 61 passed with the new warning text, the checked «Запомнить», and a new `waiting-status` snapshot.
- Instrumented on `Pixel_8_API_37_1_DeviceBridge` through `adb -s emulator-5554 shell am instrument`: 134 tests passed. New:
  - `LocalNamePublisherDeviceTest`: the real `MulticastSocket` on the emulator's `wlan0` (10.0.2.16) claims a name, answers a legacy question from a client on the same interface with its address and sends a goodbye after close.
  - `HomeScreenTest`: the address by name with a copyable IP fallback; every name problem is explained and the settings open when they can help.
  - `SettingsScreenTest`: «Имя в сети» with the `.local` suffix, errors, the restart hint, the certificate hint.
  - `EventNotificationPublisherDeviceTest`: the posted pairing notification has «Отклонить», «Разрешить», «Разрешить и запомнить», and only the allow buttons require an unlock.

## End to end on the emulator

Emulator in plain HTTP mode, headless Chrome through the adb forward, the page opened as `http://devicebridge.local:8787`. 17 checks passed:

1. The phone shows only `http://devicebridge.local:8787`; the IP is not shown while the name is held. Opening `http://10.0.2.16:8787/` ends on `http://devicebridge.local:8787/` with the page served there; the API is not served by IP (the refusal itself, HTTP 403, is covered by `KtorServerRuntimeFactoryTest`).
2. The page opens by name; «Запомнить этот браузер» is checked; pairing with «Разрешить и запомнить» stores the remembered login.
3. Stopping the server moves the tab to «Телефон недоступен»; starting it again connects the tab without a code and without a reload (0.3 s after the phone showed «Сервер запущен»); the text draft is kept; the address stays by name.
4. With the app in the background a second browser's request shows «Отклонить», «Разрешить», «Разрешить и запомнить»; «Разрешить» in the shade connects it without remembering it, and the notification goes away.

## Manual checks on the real phone and computer (2026-09-25)

Phone on the home Wi-Fi at 192.168.0.90, Windows 11 on 192.168.0.11 with the network profile «Общедоступная».

- Windows resolves `devicebridge.local` and opens the page by name; the address by IP leads to the name. With a system proxy on (here a VPN client), `*.local` has to be in its exceptions, otherwise the browser sends the name to the proxy and gets 503.
- HTTPS by name with the root created before custom names: `https://devicebridge.local:8787` serves a certificate for `devicebridge.local` and 192.168.0.90, and Windows builds a valid chain to the installed root.
- A second device with the same name, played by this computer answering `devicebridge.local` over mDNS:
  - the running phone gave the name up at once and switched to the address by IP with the notice on its home screen;
  - after a restart the phone probed `devicebridge.local`, got the answer, claimed `devicebridge-2.local` and announced it twice;
  - Windows resolved `devicebridge-2.local` to the phone, the page opened by it, the address by IP redirected to it, and the phone refused `devicebridge.local` with 403.
  - once the stand-in stopped and sent its goodbye, a restart put the phone back on `devicebridge.local`, and `devicebridge-2.local` no longer answered.

## Not covered automatically

The emulator's network is not a real Wi-Fi with a Windows computer, so these need the manual check of task 8.3:

- HTTPS by name after a certificate reset (a new root for every `.local` name).
- Windows with the network profile «Частная» (only «Общедоступная» was checked).
- The WebSocket route uses the same Host set as the page and the API; it is not tested by name separately.
