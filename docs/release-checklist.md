# DeviceBridge: release checklist MVP 1.0

Чеклист применяется к одному artifact, собранному из одного commit. `NOT RUN`,
`FAIL` и `BLOCKED` никогда не считаются успехом.

## Идентичность кандидата

- [ ] Worktree чистый, commit зафиксирован полным SHA.
- [x] `versionName` непустой, `versionCode` монотонно увеличен.
- [x] Production web bundle пересобран, `webAssetVersion` записан.
- [x] APK подписан внешним release-key и прошёл `apksigner verify`.
- [x] SHA-256 APK записан в verification report.
- [x] Сертификат совместим с предыдущей release-версией.

## Автоматические gates

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleRelease :app:verifyReleasePolicy :app:generateReleaseMetadata
cd web
npm run typecheck
npm test
npm run build
npm run test:visual
npm run test:compatibility
npm run test:file-transfer-gate
npm run verify:release-policy
```

- [x] Android JVM/Ktor: 482/482, 141 suites.
- [x] Android Compose/instrumentation support gate: 116/116 на API 29 и 116/116 на API 37.1 AVD.
- [x] Android lint: 0 errors; 66 non-blocking warnings.
- [x] Unsigned technical `assembleRelease` и metadata generation.
- [x] Web unit: 211/211, 39 files; typecheck и production build.
- [x] Playwright visual: 51 applicable PASS, 9 configuration skips.
- [x] Playwright compatibility: 6/6 Chrome/Edge.
- [x] Playwright file-transfer: 14/14 Chrome/Edge, включая 500 MiB SHA-256.
- [x] Release policy: 2/2 fixtures и 8 production/configuration roots.
- [x] Подписанный distribution artifact и release certificate.

## Artifact inspection

- [x] `debuggable=false`.
- [x] Production web manifests внутри APK совпадают с release assets.
- [x] `/diagnostics/*` и diagnostics classes отсутствуют в release.
- [x] Permissions соответствуют ТЗ; широкого storage permission нет.
- [x] Native libraries перечислены для `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`.
- [x] Secret/external endpoint policy scan пройден.
- [x] `apksigner verify --verbose --print-certs` возвращает успех.

## Обязательная ручная матрица

| Среда / сценарий | Статус текущего signed candidate |
| --- | --- |
| API 29 clean install и полный smoke | `PASS`: signed `2/1.0.1`; start/stop/notification, pairing, text/link, два последовательных файла, Share Target, history/settings, SAF и lifecycle проверены; local-network runtime permission для API 29 не применяется |
| API 37.1 clean install и полный smoke | `PASS`: signed `2/1.0.1`; start/stop/notification, deny/retry/allow local-network permission, pairing, text/link, два последовательных файла, Share Target, history/settings, SAF и lifecycle проверены |
| Update поверх предыдущей совместимо подписанной версии | `PASS`: `1/1.0` → `2/1.0.1`, settings/trusted browser/history/SAF сохранены |
| Chrome + Edge, Windows 11 | `BLOCKED`: automated browser gates PASS, signed-candidate acceptance не выполнен |
| Chrome + Edge, Windows 10 | `NOT RUN` |
| Реальная передача 500 MiB в обе стороны, cancel и network interruption | `BLOCKED`: automated streaming gates PASS, signed-candidate run не выполнен |
| Работа без Интернета и наблюдаемое отсутствие внешнего egress | `NOT RUN` |

## Правило verdict

`READY` разрешён только при release-подписи, чистой идентичности artifact,
отсутствии unresolved Critical/High findings и `PASS` во всех обязательных ячейках.
Текущий verdict: `BLOCKED`.
