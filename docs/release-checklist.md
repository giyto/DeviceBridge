# DeviceBridge: release checklist MVP 1.0

Чеклист применяется к одному artifact, собранному из одного commit. `NOT RUN`,
`FAIL` и `BLOCKED` никогда не считаются успехом.

Текущий candidate: `4/1.0.3`, commit `79cd4f5`, SHA-256 `cdedaccb…834b`
(полные значения — в `docs/verification/mvp-security-release.md`).

## Идентичность кандидата

- [x] Worktree чистый, commit зафиксирован полным SHA.
- [x] `versionName` непустой, `versionCode` монотонно увеличен.
- [x] Production web bundle пересобран, `webAssetVersion` записан.
- [x] APK подписан внешним release-key и прошёл `apksigner verify`.
- [x] SHA-256 APK записан в verification report.
- [x] Сертификат совместим с предыдущей release-версией.

## Автоматические gates

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:verifyReleasePolicy :app:assembleDistributionRelease --no-configuration-cache
.\gradlew.bat :app:connectedDebugAndroidTest --no-parallel
cd web
npm run typecheck
npm test
npm run build
npm run test:visual
npm run test:compatibility
npm run test:file-transfer-gate
npm run verify:release-policy
```

- [x] Android JVM/Ktor: 488/488, 142 suites.
- [x] Android Compose/instrumentation: 119/119 на API 29 и 119/119 на API 37.1 AVD.
- [x] Android lint: 0 errors; 66 non-blocking warnings.
- [x] `assembleDistributionRelease`, policy scan и metadata generation.
- [x] Web unit: 212/212, 39 files; typecheck и production build.
- [x] Playwright visual: 51 applicable PASS, 9 configuration skips.
- [x] Playwright compatibility: 6/6 Chrome/Edge.
- [x] Playwright file-transfer: 14/14 Chrome/Edge, включая 500 MiB SHA-256.
- [x] Release policy: 2/2 fixtures и 8 production/configuration roots.
- [x] Подписанный distribution artifact и release certificate.

Web gates выполнены на commit `b885044`; commit `79cd4f5` не меняет web-код и assets.

## Artifact inspection

- [x] `debuggable=false`.
- [x] Production web assets внутри APK совпадают с release build output.
- [x] `/diagnostics/*` и diagnostics classes отсутствуют в release.
- [x] Permissions соответствуют ТЗ; широкого storage permission нет.
- [x] Native libraries перечислены для `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`.
- [x] Secret/external endpoint policy scan пройден.
- [x] `apksigner verify --verbose --print-certs` возвращает успех.

## Обязательная ручная матрица

| Среда / сценарий | Статус текущего signed candidate |
| --- | --- |
| API 29 clean install и полный smoke | `PASS`, включая Share Target файлов |
| API 37.1 clean install и полный smoke | `PASS`, включая deny/retry/allow local-network permission и Share Target файлов |
| Update поверх предыдущей совместимо подписанной версии | `PASS`: `3/1.0.2` → `4/1.0.3` на API 29 и API 37.1 |
| Chrome + Edge, Windows 11 | `PASS` |
| Реальная передача 500 MiB в обе стороны, cancel и network interruption | `PASS`; расхождения статусов после cancel/interruption — F2 (Medium) |
| Отсутствие внешнего egress (наблюдение) | `PASS` |
| Работа без Интернета | `PASS`: ручная проверка владельцем продукта 2026-09-22 |

Windows 10 исключена из целевой матрицы MVP 1.0 решением владельца продукта.

## Правило verdict

`READY` разрешён только при release-подписи, чистой идентичности artifact,
отсутствии unresolved Critical/High findings и `PASS` во всех обязательных ячейках.
Текущий verdict: `READY` для `4/1.0.3`. Открытые F2 (Medium) и F3–F7 (Low) не блокируют.
Перед публикацией повторить dependency advisory review.
