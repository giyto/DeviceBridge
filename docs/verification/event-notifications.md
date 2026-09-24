# Event notifications verification

Change: `add-event-notifications`. Date: 2026-09-24.

## Automated checks

- Android unit: `.\gradlew.bat :app:testDebugUnitTest` - 606 tests, 0 failures. New suites:
  - `AppVisibilityTrackerTest`;
  - `EventNotificationModelTest`, `EventNotificationPlannerTest`, `EventNotificationCoordinatorTest`;
  - `EventNotificationActionHandlerTest`, `EventNotificationContractTest`;
  - `TrustedAutoAcceptControllerTest` (3 new cases for «Принять» from a notification).
- `:app:lintDebug` and `:app:verifyReleasePolicy` pass. Lint flagged a `WrongConstant` in `TlsKeyStore` (digests passed through an array); the call now lists the constants directly.
- Web: `npm test` - 42 files, 269 tests; `npm run test:compatibility` - 10 passed (Chrome and Edge); `npm run test:visual` - 51 passed with the notification panel in the snapshots; `npm run typecheck`; `npm run verify:release-policy`.
- The compatibility check serves the page under an HTTPS origin, grants the notification permission, hides the tab and receives a phone text: exactly one notification, `(1)` in the title, no service worker left registered.
- Instrumented on `Pixel_8_API_37_1_DeviceBridge` (`adb -s emulator-5554 shell am instrument`, packages `server`, `app`, `feature`, `core`, `data.tls`): 114 tests passed. `ServerProductionCyclesInstrumentedTest` expects plain HTTP, so secure mode was switched off on the emulator after the end-to-end run. `EventNotificationPublisherDeviceTest` now clears the app's notifications first, since results of real use stay in the shade. New or changed suites:

| Suite | Result |
| --- | --- |
| `EventNotificationPublisherDeviceTest` | channel «События» with high importance, lock-screen version without content, cancel |
| `NotificationOpenSectionTest` | `home`, `text`, `files` open the right screen; recreation does not navigate again |
| `SettingsScreenTest` | row «Уведомления о событиях» links to the system settings |
| `HomeScreenTest` | a new pairing request is scrolled into view |

## End to end on the emulator

Emulator in secure mode, headless Chrome trusting the phone's server certificate by its key, the app in the background (`KEYCODE_HOME`). 26 checks passed:

1. A pairing request shows «Запрос подключения» with the browser label and only «Открыть»; no pairing code; `VISIBILITY_PRIVATE` with a public version.
2. «Открыть» opens the app with the request visible; approving removes the notification.
3. A text shows «Текст с компьютера» with its beginning and «Копировать»; «Копировать» closes the notification.
4. Three files are one notification «3 файла с компьютера» with «Принять» and «Отклонить». «Принять» saved all three into the default folder without opening the app; the result is «Сохранено 3 файла»; the browser shows all three completed.
5. Without a default folder the offer shows only «Открыть» and «Откройте приложение, чтобы выбрать папку».
6. «Показать» opens the files screen and closes the result.
7. «Отклонить» on two files cancels them; the browser shows the cancel; no result is reported.
8. The page on the phone's real HTTPS turns notifications on; with the tab hidden a phone text gives one notification «Текст с телефона», the title shows `(1)`, no service worker stays registered, returning restores the title.
9. A single saved file reports «Сохранён 1 файл». Stopping the server removes the text notification and keeps the result.

The check found and fixed three issues:

- **App visibility.** It was tracked by start and stop. Android stops the activity only after the launcher animation, so a text arriving a moment after «Home» counted as seen. It is now tracked by resume and pause.
- **Open buttons.** «Открыть» and «Показать» left their notification in the shade. The app now closes the notification it was opened from.
- **Abandoned offers.** An offer nobody accepted, left behind by a closed browser or a stopped server, was reported as «не удалось». Only transfers that started count now.

A second live run, on the emulator in plain HTTP mode, covered the rest of the phone cases. 9 checks passed:

- An undecided pairing request leaves the shade when it expires.
- With «Автоприём от запомненных браузеров» on, a remembered browser's file gives no offer notification, only «Сохранён 1 файл».
- A link shows «Копировать» and «Открыть»; «Открыть» hands it to Chrome on the phone.
- With the notification permission revoked, Settings says «Выключены в системе»; after it returns, «Включены».

After these runs the instrumented suites were repeated: 114 tests passed.

Not checked automatically: the clipboard content after «Копировать» (`cmd clipboard` is not available on the image). This is covered by `EventNotificationActionHandlerTest`.

## Manual check on Windows 11

Done by the owner on 2026-09-24 with a Xiaomi phone in secure mode (task 6.3). All points of the manual checklist passed.

The page first showed «Запрещены в браузере». The browser had notifications blocked for the site. After they were allowed in the site settings and the page was reloaded, everything worked. Known limitation: the panel reads the permission only when the page loads, so a change in the site settings shows after a reload.
