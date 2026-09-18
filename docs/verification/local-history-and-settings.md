# Local history, settings and editable drafts verification

## Automated acceptance — 2026-09-18

- Change: `add-local-history-and-settings`.
- Android JVM: 130 suites, 419 tests, 0 failures, 0 errors and 0 skipped.
- Web: 27 Vitest files and 135 tests passed; strict TypeScript typecheck and Vite production build passed.
- Android packaging: debug APK, debug Android test APK and release resources assembled successfully with the bundled web assets, Room schema and launcher resources.
- Android connected: 77/77 tests passed on the `Pixel_4` API 29 AVD and 77/77 passed on the `Pixel_8_API_37_1_DeviceBridge` API 37.1 AVD.
- Chrome/Edge file-transfer gate: 14/14 Playwright tests passed, including editable multiple-file drafts, cancel preservation, remove, clear, reselect, native download, expired/single-use grant and interrupted download handling.
- Browser 500 MiB incremental SHA-256: both branded browsers produced `47c2e1d3068c1e31e17d7d6a2787f22ce486bc224772bbdc54fff13598306290`; sampled retained JS heap growth was 0 bytes.
- Android API 29 production 500 MiB route test passed in both directions with SHA-256 `79889fe7faa7462ec5005c3bd25472a46ab221fc953307559a68dcc6cdfefd71`. Browser → Android completed in 5,372 ms with 105 UI ticks and a 17,768 KiB peak PSS delta. Android → Browser completed in 9,804 ms with 191 UI ticks and a 14,940 KiB peak PSS delta.

The automated matrix covers Room/DataStore persistence, retention, safe history previews, filters/details/delete/clear, settings validation and SAF destination recovery, trusted-browser exchange/revoke/expiry, Android and browser editable file drafts, staged-source cleanup, production-route privacy, backup exclusions, failure isolation, light/dark UI and large font behavior.

## Launcher icon acceptance — 2026-09-18

- The debug APK was installed independently on API 29 and API 37.1 after the connected tests.
- Pixel Launcher exposed the exact app label `DeviceBridge` on both devices.
- API 29 rendered the legacy/adaptive icon inside the launcher circular mask without clipping; the phone, monitor and bridge remain readable at launcher size.
- API 37.1 rendered the standard adaptive icon without clipping.
- The API 37.1 launcher `Minimal` icon style was then selected and applied. The installed DeviceBridge icon switched to the monochrome layer and preserved the two-device/bridge silhouette.
- Resource tests and Android lint also confirm the standard, round and monochrome declarations and the absence of template WebP launcher assets.

Evidence:

- [API 29 launcher](./devicebridge-icon-api29.png)
- [API 37.1 launcher](./devicebridge-icon-api37.png)
- [API 37.1 Minimal/themed launcher](./devicebridge-icon-api37-themed.png)

## User manual acceptance — 2026-09-18

The user confirmed the combined physical Android ↔ desktop browser matrix:

- history records appear after text, link and file terminal results; filters, details, single delete and clear-all work correctly;
- device name, retention, effective file limit and default destination survive application restart;
- remembered browser reconnects after reload and a full browser restart, while revoke returns it to pairing;
- production server reuses port `8787`, preserving the browser origin required for trusted credential storage;
- saved destination, change-folder and revoked-permission recovery work with the phone's document provider;
- Android and browser drafts allow multiple selection, remove, clear, cancel preservation and selecting a removed file again;
- the installed launcher icon is acceptable on the physical phone;
- text/link and multi-file transfer, cancellation, retry and download/save actions behave correctly in the accepted flow.

The user reported the full manual matrix successful and authorized completion, commit, push and OpenSpec archival.
