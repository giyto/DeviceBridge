## Context

См. `proposal.md` — Ktor 3.5.2/CIO принят техническим spike, а web shell уже собирается в APK и раздаётся debug composition root. Production release пока не содержит server runtime, основной `HomeViewModel` показывает только статическое состояние `Stopped`, а permission flow существует лишь в экспортируемом debug diagnostics screen.

Change пересекает Android lifecycle, foreground service, сеть, разрешения, Hilt, Ktor source sets, Compose state и уведомления. Он следует `docs/technical-specification.md` 2.0 и этапу 4 `openspec/roadmap.md`: одно Android-приложение, Clean Architecture/MVVM/SOLID, API 29–37.1, локальный HTTP только в доверенной сети и отсутствие production pairing/API до следующего change.

Проверенный стек: Kotlin 2.2.10, AGP 9.3.2, Compose BOM 2026.02.01, Lifecycle 2.11.0, Navigation 2.10.0, Ktor 3.5.2 и Kotlinx Serialization 1.9.0. Hilt добавляется впервые.

## Goals / Non-Goals

**Goals:**

- создать один process-wide production server lifecycle с достоверным state;
- удерживать работающий listener в foreground service `connectedDevice`;
- дать Compose UI и уведомлению единый поток состояния и идемпотентные команды;
- публиковать достижимый IPv4 LAN URL со свободным портом;
- корректно обрабатывать API 37.1 LAN permission, notification permission и смену сети;
- перенести только web-serving часть принятого Ktor adapter в production;
- обеспечить unit/instrumented/manual проверки API 29, API 37.1, Wi-Fi и hotspot.

**Non-Goals:**

- pairing, session/trusted-browser tokens и Android Keystore;
- `/api/v1/*`, production WebSocket и reconnect;
- передача текста, ссылок или файлов;
- Room/DataStore, история и настройки;
- автозапуск после boot, PWA, discovery через облако/mDNS и отдельный desktop client;
- Wi-Fi lock: он появится только вместе с активной файловой передачей.

## Decisions

### 1. Разделить domain-контракт, process state и Android adapters

Поток зависимостей:

```text
Compose -> HomeViewModel -> Start/Stop use cases -> ServerLifecycleRepository
                                                   ^
                                                   |
                     Android repository -> foreground service
                                         -> server runtime
                                         -> LAN resolver/observer
                                         -> notification controller
```

В `domain` появятся неизменяемые `ServerLifecycleState`, `ServerEndpoint`, причины ошибок, repository-интерфейс и небольшие use cases. Они не импортируют Android/Ktor. Process-wide state store и Android repository живут в `data/server`; service, Activity и ViewModel получают один repository binding через Hilt.

Permission dialog остаётся UI-операцией: ViewModel выдаёт одноразовый UI effect с продуктовыми требованиями разрешений, Compose запускает Activity Result API и возвращает результат action-ом. Android permission names и API level не попадают в domain.

Альтернатива — Activity напрямую запускает service и хранит его state. Она отклонена: при пересоздании Activity появляются два источника истины, ViewModel начинает зависеть от Android lifecycle, а notification action не может согласованно обновить UI.

### 2. Ввести Hilt как production composition root

`DeviceBridgeApplication` становится Hilt application, `MainActivity` и foreground service — Android entry points. Constructor injection используется для собственных классов, `@Binds` — для domain interfaces, `@Provides` — только для Android/Ktor объектов, которыми проект не владеет. Process state и runtime coordinator получают application scope; Activity не владеет socket.

Version catalog фиксирует совместимые версии Hilt и compile-time processor. Java/Kotlin target обновляется до 17, как требует актуальное руководство Hilt. Совместимость выбранных Hilt/KSP версий с Kotlin 2.2.10 и AGP 9.3.2 проверяется отдельной чистой Gradle-сборкой до функциональных изменений.

Ручной `AppContainer` отклонён: roadmap явно вводит Hilt на этом этапе, а service/Activity/ViewModel уже образуют несколько Android lifecycle scopes.

Официальное основание: https://developer.android.com/training/dependency-injection/hilt-android

### 3. Foreground service запускается только из видимого пользовательского действия

После успешного обязательного permission preflight repository отправляет явную start-команду неэкспортируемому service через `startForegroundService`. Service немедленно публикует notification «Запускается» посредством `ServiceCompat.startForeground` с типом `FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE`, а затем открывает server runtime. Это исключает нарушение системного deadline и не маскирует медленный bind.

Manifest объявляет:

- `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`;
- `ACCESS_LOCAL_NETWORK`;
- `FOREGROUND_SERVICE` и `FOREGROUND_SERVICE_CONNECTED_DEVICE`;
- `CHANGE_NETWORK_STATE` как prerequisite типа `connectedDevice`, но приложение не меняет сеть;
- `POST_NOTIFICATIONS`.

Service имеет `android:foregroundServiceType="connectedDevice"`, `android:exported="false"`, возвращает `START_NOT_STICKY` и не имеет boot receiver. Ошибки `ForegroundServiceStartNotAllowedException`, `SecurityException` и startup runtime преобразуются в безопасное состояние, после чего foreground/service завершаются.

Альтернативы `dataSync` и WorkManager отклонены: server ожидает локального внешнего устройства без ограниченного срока, а Android рекомендует `connectedDevice` для связи/передачи по local internet connection.

Официальные основания:

- https://developer.android.com/develop/background-work/services/fgs/launch
- https://developer.android.com/develop/background-work/services/fgs/service-types
- https://developer.android.com/develop/background-work/background-tasks/data-transfer-options

### 4. Notification создаётся до bind и управляется из фактического state

Один low-priority notification channel создаётся идемпотентно. Notification имеет `ongoing`-состояние, название DeviceBridge, фазу lifecycle, endpoint после успешного bind, ноль browsers/отсутствие transfer на этом этапе и explicit immutable `PendingIntent` «Остановить», адресованный service action.

Stop action вызывает тот же coordinator, что и UI: сначала закрываются runtime/network subscriptions, затем удаляется foreground notification и вызывается `stopSelf`. Notification не содержит codes, tokens или диагностические сведения.

На API 33+ UI объясняет и запрашивает `POST_NOTIFICATIONS`. Отказ не запрещает старт foreground service на платформах, где Android допускает его без drawer notification: UI показывает предупреждение и всегда сохраняет in-app Stop. Системный Task Manager остаётся дополнительным способом остановить весь процесс; Android не гарантирует callback, поэтому новый процесс начинает с `Stopped`.

Официальные основания:

- https://developer.android.com/develop/ui/compose/notifications/notification-permission
- https://developer.android.com/develop/background-work/services/fgs/stop-fgs
- https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping

### 5. LAN permission имеет блокирующий и неблокирующий preflight

`ACCESS_LOCAL_NETWORK` запрашивается только на API 37+ и является обязательным для запуска socket. На более старых API этот шаг считается удовлетворённым. `POST_NOTIFICATIONS` на API 33+ запрашивается рядом, но его отказ не блокирует server start.

Во время работы Android adapter следит за изменением permissions для UID приложения и повторно проверяет доступ перед сетевыми операциями. Отзыв LAN permission вызывает одну stop-команду с причиной `PermissionRevoked`. `SecurityException` от сетевого API является дополнительным fail-closed сигналом.

Альтернатива — просить все разрешения при первом запуске приложения. Она отклонена: Android требует контекстного runtime request, а DeviceBridge должен объяснять LAN-доступ непосредственно перед явным запуском.

Официальное основание: https://developer.android.com/privacy-and-security/local-network-permission

### 6. Endpoint выбирается до публикации и связывается с network fingerprint

`LanEndpointResolver` возвращает либо один `ServerEndpointCandidate`, либо типизированную ошибку `NoLanNetwork`/`AmbiguousLanNetwork`. Алгоритм:

1. предпочесть non-loopback IPv4 из `LinkProperties` активной подходящей Wi-Fi сети;
2. для hotspot/AVD fallback рассмотреть поднятые non-loopback интерфейсы с private IPv4;
3. исключить loopback, link-local, multicast и unspecified адреса;
4. если fallback оставляет несколько неразличимых кандидатов, не угадывать адрес, а показать recoverable error.

После выбора runtime привязывается к all-local bind address и порту `0`; опубликованный endpoint формируется только из выбранного IPv4 и фактического bound port. Ktor Host allowlist строится из этого endpoint. Пользователю никогда не показываются `0.0.0.0`, `127.0.0.1` или порт до успешного bind.

Такой fallback нужен для точки доступа, чей downstream interface не обязан быть default network. Полный автоматический discovery не добавляется.

### 7. Network callback передаёт снимки, а не выполняет синхронные повторные запросы

`LanNetworkObserver` регистрирует ровно один `ConnectivityManager.NetworkCallback` на время server lifecycle и всегда unregister-ит его при stop. Он использует аргументы `onCapabilitiesChanged`/`onLinkPropertiesChanged`; внутри callback не вызываются синхронные `getNetworkCapabilities`/`getLinkProperties`, чтобы избежать race.

Coordinator хранит network fingerprint выбранного endpoint. Потеря network, disappearance адреса или изменение fingerprint приводит к идемпотентной stop с `NetworkLost`/`AddressChanged`. Новая сеть обновляет доступность запуска, но не открывает socket автоматически. Это разрывает будущие сессии fail-closed и не переносит доверие между сетями.

Официальные основания:

- https://developer.android.com/reference/android/net/ConnectivityManager
- https://developer.android.com/reference/android/net/ConnectivityManager.NetworkCallback

### 8. Одна mutex-защищённая state machine управляет runtime

State machine:

```text
Stopped -> Starting -> Running -> Stopping -> Stopped
              |            |
              +-> Error <-+
Error --explicit retry--> Starting
```

Start/stop сериализуются mutex-ом. Каждая попытка получает generation id, поэтому поздний callback старого runtime не может перезаписать новое состояние. `Running` хранит endpoint и монотонную отметку старта; uptime вычисляется через внедрённый monotonic clock и не сериализуется как источник истины.

Cleanup идемпотентно закрывает server runtime, clients, child coroutines, network/permission observers и notification даже после частичного startup. Stop имеет ограниченное время ожидания; после него service отменяет scope и завершает себя, не блокируя main thread.

Сохранять `Running` в SavedState/DataStore отклонено: сохранённый флаг не доказывает наличие живого socket. Activity всегда читает process-wide actual state; новый процесс создаёт `Stopped`.

### 9. Production Ktor composition не включает diagnostics

Проверенные server runtime и web routes перемещаются из debug-only реализации в production packages за domain interface. Ktor/CIO/serialization становятся production dependencies. Release composition регистрирует только `/`, allowlisted `/assets/*` и `/web-manifest.json`; все `/api/v1/*` остаются отсутствующими.

Debug source set добавляет диагностический Activity, token и `/diagnostics/*` поверх отдельного debug composition root. Production service никогда не получает диагностический token и не использует diagnostic routes. Контракт web assets, CSP, cache и Host/Origin policy сохраняется, но allowlist получает фактический LAN endpoint.

Копировать debug factory целиком в main отклонено: это может случайно опубликовать diagnostics и временную модель bearer token.

### 10. TDD и матрица проверяют state, Android contracts и реальную LAN-доступность

Чистые reducer/coordinator/resolver тесты пишутся до реализации и используют fake runtime, clock, permissions, notification и network observer. Service/manifest/notification/permission contracts проверяются instrumented-тестами на API 29 и API 37.1. Реальный Ktor lifecycle повторяет 20 циклов и проверяет production web routes.

AVD проверки используют `adb forward` для endpoint reachability. Отдельная ручная проверка на физическом устройстве подтверждает показанный Wi-Fi URL и common hotspot URL с Chrome/Edge; без неё hotspot requirement не считается полностью проверенным. Проверка notification stop выполняется на обеих API, а API 37.1 дополнительно покрывает grant, denial и revoke.

## Risks / Trade-offs

- [Несколько private IPv4 из VPN/виртуальных интерфейсов] → предпочитать active Wi-Fi `LinkProperties`, не угадывать неоднозначный fallback и показывать recoverable error.
- [Hotspot downstream не представлен default network] → использовать ограниченный interface fallback и обязательную physical-device проверку; не заявлять discovery.
- [FGS запущен, но Ktor bind упал] → сначала показать notification «Запускается», затем очистить runtime, notification и service в едином error path.
- [Notification permission отклонён] → оставить сервер управляемым на главном экране, показать предупреждение и учитывать системный Task Manager.
- [Network callback приходит после stop/restart] → generation id и идемпотентный cleanup игнорируют устаревшее событие.
- [Продвижение Ktor в release увеличит APK] → зафиксировать baseline/delta, проверить R8/package и отдельно доказать отсутствие diagnostics.
- [Hilt/KSP конфликтует с AGP built-in Kotlin] → сначала зафиксировать совместимые официальные версии и выполнить чистый compile smoke; при конфликте остановить implementation до выбора поддерживаемой комбинации, не обходить generated DI вручную.
- [HTTP остаётся незашифрованным] → сохранить видимое предупреждение и не добавлять пользовательские API до pairing/security changes.

## Migration Plan

1. Зафиксировать baseline test/APK и совместимые Hilt/KSP/Java 17 настройки.
2. Добавить pure domain state/repository/use cases и unit-тесты без изменения UI.
3. Ввести Hilt composition root и process-wide state store.
4. Реализовать LAN resolver/observer, permission gate, notification controller и coordinator через fake-first TDD.
5. Перенести production-safe Ktor runtime/web routes в main, оставив diagnostics в debug.
6. Добавить foreground service/manifest и instrumented lifecycle/notification/permission tests.
7. Подключить HomeViewModel/Compose permission effects, endpoint/copy/uptime UI и UI-тесты.
8. Выполнить API 29/API 37.1, Chrome/Edge, Wi-Fi/hotspot, APK/security regression и OpenSpec quality gate.

Rollback выполняется одним revert change: main spec ещё не синхронизирован до завершения, существующий debug diagnostics остаётся независимым, а web build не меняет формат assets. Если production Ktor/Hilt gate не проходит, change не архивируется и этап 5 не начинается.
