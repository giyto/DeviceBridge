## Context

Мотивация — в proposal.md. Как устроено сейчас:

- **Запуск.** `AndroidServerLifecycleRepository.start()` вызывает `startForegroundService(ACTION_START)`. `ServerForegroundService` выполняет `startForeground(..., CONNECTED_DEVICE)` и `ServerLifecycleCoordinator.start()`. Ошибку `ForegroundServiceStartNotAllowedException` сервис уже отображает в `ServerLifecycleError.ForegroundStartNotAllowed`.
- **Проверки перед запуском.** Разрешение локальной сети на API 37+ и уведомления на API 33+ проверяются только в `HomeViewModel` через `ServerPermissionPolicy`. Service и coordinator разрешения не проверяют.
- **Состояние.** `ServerLifecycleCoordinator.state` — singleton `StateFlow` со значениями Stopped, Starting, Running, Stopping и Error. Уведомление уже содержит действие «Остановить» (`ACTION_STOP`).
- **Браузеры.** Число подключённых браузеров равно `BrowserSessionState.sessions.size`, а session удаляется только при revoke или закрытии generation. Живые WebSocket-подключения `BrowserSessionCoordinator` хранит в приватном `connections`. Тот, кто закрыл вкладку, продолжает считаться подключённым.
- **Таймеры.** Таймеров простоя сервера нет. Ping WebSocket — 15 с, timeout — 30 с.
- **Платформа.** minSdk 29, targetSdk 37.

## Goals / Non-Goals

**Goals:**
- Одно касание плитки запускает или останавливает сервер через тот же lifecycle, без второго пути запуска.
- Автоостановка опирается на фактическую активность, а не на число sessions.

**Non-Goals:**
- Автозапуск сервера по сети, расписанию или событиям.
- Изменение смысла счётчика «Подключённые браузеры» на Home и в уведомлении. Он по-прежнему считает sessions. Расхождение с живыми подключениями фиксируется в Risks.
- Виджет на рабочем столе.

## Decisions

### D1. Плитка — тонкий клиент существующего lifecycle

`DeviceBridgeTileService` наблюдает `ServerLifecycleRepository.state` и `BrowserSessionState` только в `onStartListening`/`onStopListening`. Команды проходят через те же use cases `StartServerUseCase` и `StopServerUseCase`. Изменения состояния вызывают `TileService.requestListeningState`, чтобы плитка обновлялась и при закрытой шторке.

*Альтернатива:* отдельный путь запуска из плитки. Отклонена: получились бы два источника правды о состоянии.

### D2. Запуск из плитки: сначала напрямую, иначе через Activity

Порядок действий при нажатии на остановленный сервер:

1. Если устройство заблокировано, вызывается `unlockAndRun { … }`.
2. `ServerPermissionPolicy` проверяет разрешения. Если чего-то не хватает, открывается `MainActivity` с extra `START_FROM_TILE`.
3. Выполняется `startForegroundService`. Если Android запрещает запуск (`ForegroundServiceStartNotAllowedException` при вызове или состояние `Error(ForegroundStartNotAllowed)` сразу после него), тоже открывается `MainActivity` с `START_FROM_TILE`.
4. Activity открывается через `startActivityAndCollapse(PendingIntent)` на API 34+ и через `startActivityAndCollapse(Intent)` на более старых.
5. `HomeViewModel`, получив `START_FROM_TILE`, выполняет обычный `requestPermissionsOrStart()` на видимом экране.

Документация Android не даёт однозначного ответа, освобождает ли нажатие плитки от запрета на фоновый старт foreground service типа `connectedDevice`. Поэтому fallback обязателен, а фактическое поведение фиксируется E2E-проверкой на API 29 и API 37.1.

*Альтернатива:* всегда открывать приложение. Отклонена: это противоречит цели «без открытия приложения» там, где прямой запуск разрешён.

**Результат E2E на release-сборке.** На API 29 и API 37.1 нажатие плитки при свёрнутом приложении запускает сервер напрямую. Приложение открывается только при нехватке разрешения локальной сети: первый запуск на API 37.1.

**Найдено на Android 10.** Когда `unlockAndRun` вызывает runnable после ввода PIN, `isLocked` всё ещё возвращает `true`, пока keyguard уходит с экрана. Поэтому повторная проверка блокировки в этом callback не выполняется: runnable вызывается только после успешной разблокировки.

### D3. Подтверждение остановки во время передачи через `showDialog`

`TileService.showDialog` показывает `AlertDialog` поверх шторки, если есть незавершённые text или file operations. Без операций остановка выполняется сразу. Остановка разрешена без разблокировки: она только уменьшает доступность телефона в сети.

### D4. Живые подключения как отдельный `StateFlow`

`BrowserSessionCoordinator` публикует `activeConnectionCount: StateFlow<Int>` из существующих `attachConnection`/`detachConnection`. Session и счётчик на Home остаются как есть.

*Альтернатива:* удалять session при отключении. Отклонена: сломается восстановление в той же вкладке и trusted reconnect.

### D5. `IdleStopController` в application scope

- Работает, пока lifecycle находится в Running.
- Объединяет `activeConnectionCount`, `pendingRequests`, незавершённые text/file operations и настройку времени.
- Когда наступает простой, запоминает дедлайн по `SystemClock.elapsedRealtime()` и ждёт его через `delay`.
- При каждом пробуждении flow и на каждом тике проверяет дедлайн по `elapsedRealtime()`, поэтому сон устройства не продлевает интервал.
- При активности дедлайн сбрасывается.
- По истечении дедлайна вызывает `ServerLifecycleCoordinator.stop(ServerStopReason.IdleTimeout)`.
- Wake lock ради таймера не берётся: остановка при ближайшем пробуждении допустима по спеке.

*Альтернатива:* `AlarmManager`. Отклонена: точное пробуждение ради остановки не нужно, а для exact alarms нужны лишние разрешения.

### D6. Причина `IdleTimeout` — Stopped, а не Error

`ServerStopReason.IdleTimeout(minutes)` публикуется координатором в отдельном `lastStopReason: StateFlow`. Координатор записывает его раньше, чем публикует `Stopped`, и сбрасывает при следующем запуске. Тип `Stopped` не меняется, потому что его сопоставляют во многих местах. Значение живёт в памяти singleton, поэтому Home показывает его и после пересоздания Activity. После гибели процесса сервер показывается просто остановленным, как и сейчас. Error не используется, потому что это не сбой.

### D7. Настройка — enum с четырьмя значениями

`IdleStopTimeout { OFF, MIN_15, MIN_30, MIN_60 }` хранится в DataStore строкой, по умолчанию `MIN_30`. Неизвестное значение читается как default. Для E2E в debug-сборке, определяемой по `ApplicationInfo.FLAG_DEBUGGABLE`, доступно дополнительное значение 1 минута. В release-сборке репозиторий отклоняет запись этого значения и читает его как default. Это проверяется JVM-тестом. В Settings настройка находится в новой карточке «Сервер» вместе с кнопкой «Добавить плитку» (API 33+, `StatusBarManager.requestAddTileService`).

### D8. Уведомление показывает время остановки

Когда условия простоя выполняются, `ServerNotificationModel` получает `idleStopAtWallClock` и показывает строку «Остановится в 16:55 без подключений». Время не тикает каждую минуту, поэтому уведомление не обновляется лишний раз.

## Risks / Trade-offs

- **[Android запрещает фоновый старт foreground service из плитки на части версий]** → fallback через `startActivityAndCollapse` (D2). Поведение фиксируется на API 29 и 37.1.
- **[Счётчик «Подключённые браузеры» на Home не совпадает с живыми подключениями]** → в спеке автоостановки прямо указаны живые подключения. Приведение счётчика к единому смыслу — возможный отдельный change.
- **[Браузер в фоне без подключения держит сервер открытым]** → невозможно: вкладка без WebSocket не считается активной, отсчёт идёт.
- **[Пользователь недоволен остановкой по умолчанию после обновления]** → в руководстве и Settings видно значение, одно касание выключает автоостановку. На Home после остановки показана причина.
- **[Плитка на заблокированном экране]** → запуск только после `unlockAndRun`.

## Migration Plan

Новый ключ DataStore появляется со значением по умолчанию 30 минут. Для существующих пользователей это новое поведение, оно описывается в руководстве. Плитку пользователь добавляет сам, система не добавляет её автоматически. Откат версии оставит неиспользуемый ключ, вреда от него нет.
