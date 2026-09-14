## Why

После положительного Ktor/CIO spike необходимо подтвердить вторую ключевую часть одноприложенной архитектуры: браузер компьютера должен получать интерфейс непосредственно из Android APK без отдельного desktop-клиента и внешнего backend. Этот change создаёт воспроизводимый web build и минимальный браузерный shell до добавления pairing и пользовательских операций.

## What Changes

- Добавить web-проект на TypeScript, HTML и CSS с Vite, фиксированными npm-зависимостями и unit-тестами Vitest.
- Создать минимальную адаптивную страницу DeviceBridge со статусами подключения, понятной ошибкой и ручным повтором проверки; пользовательские операции на этом этапе не показывать как доступные.
- Собирать versioned web assets и автоматически включать их в Android APK через Gradle/Vite pipeline.
- Раздавать из принятого debug server adapter корневую страницу, локальные статические ресурсы и публичный `/web-manifest.json`, который считается частью web assets и содержит только версии протокола и bundle.
- Не открывать `/api/v1/status` без авторизации: защищённый API статуса появится вместе с pairing и session token.
- Ограничить web routes допустимыми `Host` и same-origin `Origin`, не включать разрешающий CORS и возвращать защитные HTTP-заголовки.
- Проверить отсутствие внешних ресурсов, аналитики, service worker и сетевых запросов за пределы same-origin DeviceBridge.
- Проверить загрузку страницы не более чем за пять секунд после установления TCP-соединения через `adb forward` на API 29 и API 37.1, адаптацию от 360 до 1920 пикселей, системную светлую/тёмную тему и базовую доступность в Chrome и Edge.
- Явно предупредить пользователя, что локальный HTTP не шифрует трафик и подходит только для доверенной сети.
- Не добавлять в этом change foreground service, production pairing, защищённый `/api/v1/status`, WebSocket-сессию и reconnect, передачу текста/файлов, историю, PWA или отдельное приложение для компьютера.

## Capabilities

### New Capabilities

- `browser-web-interface`: воспроизводимая сборка, упаковка и локальная раздача встроенного web shell со статусом соединения и offline-only ресурсами.

### Modified Capabilities

Нет.

## Impact

- Новый каталог `web/` с исходниками, тестами, `package.json` и lock-файлом.
- Gradle pipeline и Android assets, генерируемые сборкой Vite.
- Debug Ktor routes для `/`, `/assets/*` и `/web-manifest.json`; диагностические маршруты `/diagnostics/*` остаются изолированными, а `/api/v1/*` не открывается до реализации авторизации.
- Новые build-time зависимости Node.js/npm/Vite/Vitest без web runtime-фреймворка.
- Release APK получает только статические web assets; Ktor server и debug harness по-прежнему не попадают в release до следующего production lifecycle change.
