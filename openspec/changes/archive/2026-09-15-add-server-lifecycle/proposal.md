## Why

После принятых server spike и встроенного web shell DeviceBridge всё ещё не умеет запускать сервер как пользовательскую production-функцию: Ktor и управление lifecycle доступны только в debug diagnostics. До pairing необходимо перенести проверенный server adapter в foreground service, связать его с реальным состоянием приложения и безопасно реагировать на разрешения и изменения локальной сети.

## What Changes

- Добавить production lifecycle локального сервера: запуск только по явному действию пользователя, состояния запуска/работы/остановки/ошибки и полное освобождение ресурсов.
- Запускать сервер в foreground service типа `connectedDevice` с постоянным уведомлением, актуальным состоянием и действием «Остановить».
- Добавить пользовательские flows `ACCESS_LOCAL_NETWORK` на API 37.1 и `POST_NOTIFICATIONS` на поддерживаемых версиях; отказ в уведомлениях не скрывает in-app управление, а отказ или отзыв LAN-разрешения не оставляет порт открытым.
- Определять доступный локальный IPv4-адрес и свободный порт, показывать реальный browser URL и поддерживать Wi-Fi LAN и точку доступа телефона без внешнего discovery/backend.
- Наблюдать за сетью через Android connectivity API; при потере сети, смене адреса или отзыве разрешения останавливать старый listener и требовать явного повторного запуска с новым адресом.
- Подключить Hilt для production composition root и сохранить границы Clean Architecture/MVVM: ViewModel и domain не зависят от Service, Ktor, ConnectivityManager или Android permission API.
- Перенести принятый Ktor 3.5.2/CIO adapter и раздачу встроенного web shell в production, сохранив `/diagnostics/*` и диагностический token исключительно в debug.
- Обновить главный экран: запуск/остановка, состояния service, адрес с копированием, время работы и понятные recoverable errors; после пересоздания Activity экран восстанавливает фактическое состояние работающего service.
- Запретить автозапуск после перезагрузки, скрытый background start, pairing, session token и передачу данных в рамках этого change.

## Capabilities

### New Capabilities

- `server-lifecycle`: production foreground service, разрешения, LAN endpoint, наблюдение за сетью, уведомление, восстановление состояния и безопасная остановка локального сервера.

### Modified Capabilities

- `android-app-shell`: главный экран начинает управлять реальным сервером, показывает его endpoint и продолжает блокировать передачу до авторизации браузера.

## Impact

- Android manifest и permissions, foreground service, notification channel/action и Compose permission launchers.
- Пакеты `domain`, `data/server`, `feature/home` и application composition root.
- Gradle version catalog и app dependencies: Hilt с compile-time generation, Ktor/CIO/serialization в production; диагностические маршруты остаются debug-only.
- Unit-, service-, Compose- и instrumented-тесты на API 29 и API 37.1, включая network change, permission denial/revocation, notification stop и повторные lifecycle-циклы.
- Web assets и публичные static routes сохраняют текущий контракт; production `/api/v1/*`, pairing и transfer endpoints не добавляются.
