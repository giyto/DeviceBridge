# Bundled browser web interface

## Цель

Проверить воспроизводимую сборку браузерного интерфейса, его упаковку в единственный Android APK и безопасную локальную раздачу без внешнего backend.

## Baseline до web assets

- Git commit: `e6ed13239d27f7ffba09e1a7066968ec8fc58fd2`
- ОС сборки: Windows 11 10.0 amd64
- JDK: Eclipse Temurin 21.0.7+6 LTS
- Gradle: 9.5.0
- Android Gradle Plugin: версия из `gradle/libs.versions.toml`

| APK | Размер, байт | SHA-256 |
| --- | ---: | --- |
| `app-debug.apk` | 36 876 484 | `390C1FBAA9675810D4497908434E904C5EAAB5F1F9B177392464639B923E781F` |
| `app-release-unsigned.apk` | 23 019 013 | `05F0F7A430CCCA471365DE92C002C963EB1294D140CC20F29039147FDD945DE5` |

Команды воспроизведения:

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleRelease --console=plain
Get-Item app\build\outputs\apk\debug\app-debug.apk
Get-FileHash -Algorithm SHA256 app\build\outputs\apk\debug\app-debug.apk
Get-Item app\build\outputs\apk\release\app-release-unsigned.apk
Get-FileHash -Algorithm SHA256 app\build\outputs\apk\release\app-release-unsigned.apk
java -version
.\gradlew.bat --version
git rev-parse HEAD
```

Обе APK-сборки завершились успешно до добавления каталога `web/` и generated assets.

## Web toolchain

- Node.js: 22.15.0 LTS, зафиксирован в `web/.nvmrc` и `package.json#engines`
- npm: 10.9.2, зафиксирован через `packageManager`
- Vite: 8.3.0
- Vitest: 5.0.0
- TypeScript: 7.0.2
- jsdom: 29.1.1, выбран вместо 30.0.1 из-за совместимости с Node 22.15.0

`npm ci` завершён успешно: установлено 79 пакетов, найдено 0 известных уязвимостей.

Gradle pipeline содержит инкрементальные `:app:npmCi` и
`:app:buildWebAssets`. Повторная сборка использовала configuration cache и
показала обе задачи как `UP-TO-DATE`.

Негативные проверки:

- отсутствующая npm-команда останавливает `:app:npmCi` до упаковки APK;
- тестовая npm-команда с кодом 0 без outputs приводит к ошибке
  `Vite web output is incomplete; refusing to package stale Android assets.`;
- перед build output очищается, после негативной проверки обычная сборка заново
  создала bundle и успешно собрала debug/release APK.

Официальные основания для выбора:

- Vite: https://vite.dev/guide/ и https://vite.dev/config/build-options.html
- Vitest: https://vitest.dev/guide/ и https://vitest.dev/config/environment
- npm clean install: https://docs.npmjs.com/cli/commands/npm-ci/

## Reproducibility manifests

Два последовательных production build дали одинаковые относительные пути,
размеры и SHA-256. Оба результата сохранены в
`docs/spikes/bundled-browser-web-interface.manifest.json`; поле
`identical` имеет значение `true`.

## Android и browser matrix

Полный `:app:connectedDebugAndroidTest` выполнен одновременно на двух Pixel AVD:

| AVD | Android/API | Результат |
| --- | --- | --- |
| Pixel 4 | Android 10 / API 29 | 18/18, 0 skipped, 0 failed |
| Pixel 8 | Android 17 / API 37.1 | 18/18, 0 skipped, 0 failed |

После установки debug APK сервер запускался на каждом AVD, а компьютер
подключался к тому же адресу `http://127.0.0.1:8787` через `adb forward`.

| API | `/` | `/web-manifest.json` | отсутствующий path | чужой `Origin` | чужой `Host` | HTML + CSS + JS |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 29 | 200 | 200 | 404 | 403 | 403 | 133 мс |
| 37.1 | 200 | 200 | 404 | 403 | 403 | 140 мс |

На обоих API подтверждены:

- CSP `default-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'`;
- `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer` и
  `Cross-Origin-Resource-Policy: same-origin`;
- `Cache-Control: no-store` для HTML/manifest и
  `public, max-age=31536000, immutable` для hashed CSS/JS;
- отсутствие `Access-Control-Allow-Origin`.

На API 37.1 отдельно отозвано `ACCESS_LOCAL_NETWORK` и в системном диалоге
выбран отказ. Порт остался закрыт, а экран показал контролируемое сообщение:
`Доступ к локальной сети запрещён. Разрешите его и повторите запуск.`

Browser matrix запускается командой:

```powershell
cd web
npm run browser:matrix
```

Проверка использует Chrome/Edge DevTools Protocol, отдельный временный профиль
и не меняет пользовательские профили браузеров.

| Браузер | Версия | Viewport и темы | Время до `available` |
| --- | --- | --- | ---: |
| Google Chrome | 153.0.8010.36 | 360/768/1920, light/dark | 97–289 мс |
| Microsoft Edge | 150.0.4078.105 | 360/768/1920, light/dark | 108–240 мс |

Во всех 12 сценариях:

- состояние стало `DeviceBridge доступен` значительно быстрее лимита 5 секунд;
- предупреждение о незашифрованном локальном HTTP было видимым;
- `scrollWidth` совпал с viewport, горизонтального overflow нет;
- реальная light/dark media query применилась;
- клавиша Tab перевела видимый focus на skip-link;
- внешних HTTP-запросов и зарегистрированных service worker нет.

Компактные machine-readable результаты сохранены в
`docs/spikes/bundled-browser-web-interface.browser-matrix.json`.

## APK после web assets

Обе APK содержат 5 runtime web assets: `index.html`, два manifest и
content-hashed CSS/JS. Все ссылки `index.html` разрешаются в файлы APK.
`.gitkeep` служит только маркером репозитория и в APK не упаковывается.

| APK | Размер, байт | Delta к baseline | SHA-256 |
| --- | ---: | ---: | --- |
| `app-debug.apk` | 36 957 502 | +81 018 | `d193b4f376297eb06366d796d7df119e18ce3e835138dad79d9bc4db4f1bc013` |
| `app-release-unsigned.apk` | 23 042 273 | +23 260 | `ab075d243de8b59f7d942e4bd39d6227e11c9c2cfc6943c6fccbb2a4d5ebdaee` |

`apkanalyzer dex packages --defined-only` подтвердил:

- `releaseContainsKtor = false`;
- `releaseContainsDiagnostics = false`.

## Architecture, security и scope review

- Web state отделён от DOM и Android: `WebManifestClient` и
  `ConnectionController` используют внедряемые HTTP/clock зависимости, а
  renderer отвечает только за отображение состояния.
- Контракт `WebAssetProvider` не зависит от Android или Ktor.
  `AssetManagerWebAssetProvider` является Android-адаптером, а Ktor routes
  находятся только в debug source set.
- Domain и `HomeViewModel` не импортируют Android/Ktor; release APK не
  содержит Ktor или diagnostics.
- Реализовано одно Android-приложение с встроенным web bundle. Отдельного
  desktop-приложения и внешнего backend нет.
- Публичны только `/`, allowlisted `/assets/*` и
  `/web-manifest.json`. `/api/v1/status` возвращает 404,
  permissive CORS отсутствует, чужие `Host`/`Origin` получают 403.
- В web shell нет pairing, форм текста/файлов или передачи пользовательских
  данных. Существующий `/diagnostics/ws` остаётся debug-only стендом
  предыдущего change; браузер к нему не подключается и reconnect не реализует.

## Ограничения этапа

Pairing, session token, защищённый API, foreground lifecycle, передача текста и файлов и WebSocket reconnect не реализуются этим change.

## Команды полного quality gate

```powershell
cd web
npm ci
npm test
npm run build
npm run browser:matrix

cd ..
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease --console=plain
.\gradlew.bat :app:connectedDebugAndroidTest --console=plain
openspec validate bundle-browser-web-interface --strict
```
