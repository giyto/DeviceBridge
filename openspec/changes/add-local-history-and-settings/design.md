## Context

См. `proposal.md` для мотивации. Текущая реализация уже разделяет Compose UI, domain repository/use-case boundaries, Ktor routes и in-memory coordinators для session, text и file transfer. `HistoryScreen` и `SettingsScreen` пока являются статическими заглушками; file selection на Android получает `transferId` до подтверждения и заменяет предыдущий selection, а browser controller также заменяет массив `selectedFiles`. Session token хранится только в `sessionStorage`, persistent storage и Room/DataStore ещё не подключены.

Изменение пересекает Android UI, transfer coordinators, Ktor protocol и web shell, вводит постоянную схему данных и новый долговременный секрет. Оно обязано сохранить одно-приложную LAN-only архитектуру, не записывать progress на каждый chunk, не блокировать transfer при ошибке history storage и не переносить Android settings/history в browser API.

## Goals / Non-Goals

**Goals:**

- добавить domain boundaries для истории, настроек и trusted browsers без доступа ViewModel к Room, DataStore, ContentResolver или Keystore;
- сохранять только terminal metadata идемпотентно и вне transfer hot path;
- обеспечить безопасный scoped destination и effective file limit;
- отделить Android/web draft selection от server queue и корректно освобождать временные sources;
- выдать persistent trust только после явного решения Android и сохранить session token generation-scoped;
- заменить template launcher resources полной adaptive/legacy/monochrome иконкой.

**Non-Goals:**

- синхронизация истории или настроек с browser, облаком или другим телефоном;
- сохранение полного текста, содержимого файлов, executable queue или восстановление незавершённой передачи после server restart;
- доверие ко всему компьютеру: credential относится только к browser profile/site storage, в котором он сохранён;
- WorkManager-задача постоянной фоновой очистки; cleanup выполняется при локальных точках активности приложения;
- изменение HTTP/WebSocket транспортной модели, добавление desktop companion или нового backend;
- полный визуальный redesign Android и web интерфейсов.

## Decisions

### 1. Новые domain boundaries вместо прямого доступа UI к storage

В domain добавляются модели и небольшие интерфейсы `HistoryRepository`, `SettingsRepository` и `TrustedBrowserRepository`, а также use cases наблюдения, изменения, фильтрации и удаления. Compose-экраны получают только `UiState` и отправляют actions во ViewModel. Transfer/session coordinators взаимодействуют с persistence через отдельные application-scoped use cases.

Это сохраняет текущую прагматичную Clean Architecture и позволяет тестировать reducers/coordinators с fake repositories. Альтернатива — передать DAO/DataStore прямо во ViewModel и coordinators — отклонена, потому что смешивает Android storage, security и presentation lifecycle.

### 2. Room хранит terminal history и trusted metadata, Preferences DataStore — обычные settings

Создаётся одна локальная Room database версии 1 с двумя независимыми таблицами:

- `history_records`: stable record id, operation id, kind, direction, normalized browser label, timestamp epoch millis, terminal status, bounded text preview и nullable file metadata;
- `trusted_browsers`: opaque record id, normalized label, timestamps, expiry, last-used и защищённый credential verifier.

`operationId + kind` имеет unique index, поэтому повторный acknowledgement, snapshot или terminal callback не создаёт дубликат. DAO отдаёт `Flow`, применяет filters в query и выполняет individual/all/retention delete транзакционно. Имена status/kind сохраняются как стабильные wire-like строки с явным mapper, а не как ordinal enum.

Preferences DataStore хранит device name, retention days, destination tree URI и effective file limit. Settings mapper всегда применяет defaults и validation; повреждённое или неизвестное значение заменяется безопасным default отдельно от остальных ключей.

Альтернатива хранить всё в DataStore отклонена: фильтрация, сортировка, unique operation id и массовое retention delete естественно относятся к Room. Альтернатива хранить settings в Room не даёт преимуществ для нескольких атомарных пользовательских параметров.

### 3. History записывается один раз на terminal transition

Application-scoped `TransferHistoryRecorder` получает terminal result из `TextTransferCoordinator` и `FileTransferCoordinator` после определения фактического результата. Он строит bounded domain record и вызывает idempotent insert на IO dispatcher. Progress, chunks, active queue и полный payload никогда не записываются.

Ошибка history write публикуется отдельным безопасным UI event/diagnostic state и не изменяет delivered/completed result, не повторяет payload и не блокирует следующую операцию. Retention cleanup запускается при инициализации history repository и после успешной вставки, используя UTC epoch boundary; отдельный background worker для MVP не нужен.

Короткий text preview создаётся до persistence как plain text максимум 200 Unicode code points. File record не содержит source/destination URI, download grant или path. Удаление record не вызывает storage/file API.

### 4. Trusted credential проверяется через Keystore-backed verifier

Raw trusted credential генерируется криптографическим RNG и возвращается browser ровно один раз. Android не сохраняет raw token: Keystore-backed HMAC key создаёт verifier, который хранится вместе с metadata в Room. При reconnect server повторно вычисляет HMAC и сравнивает verifier constant-time. Это защищает credential сильнее, чем хранение расшифровываемого token, и сохраняет секрет вне database/backup.

Pending pairing DTO получает `rememberBrowserRequested`. Android decision различает `AllowOnce`, `AllowAndRemember` и `Reject`; только второй вариант создаёт trusted record и включает credential в успешный confirmation response. Срок фиксирован максимум 30 дней от выдачи.

Добавляется same-origin `POST /api/v1/session/trusted`: payload содержит protocol version и credential, route применяет Host/Origin, rate/size limits и возвращает новый session token текущего generation. Session, созданная таким способом, хранит `trustedBrowserId`; revoke закрывает производные sessions/WebSockets и инвалидирует verifier. Credential не принимается обычным bearer middleware.

Альтернатива переиспользовать session token отклонена: он ограничен server generation и уже обещан как tab-scoped. Альтернатива хранить raw token в SharedPreferences отклонена из-за backup/logging и extraction risk.

### 5. Browser использует два независимых credential stores

`BrowserSessionTokenStore` продолжает использовать `sessionStorage`. Новый versioned `BrowserTrustedCredentialStore` использует `localStorage` только после explicit opt-in и хранит opaque credential отдельно. На загрузке порядок восстановления такой: проверить текущий session token, затем один раз попробовать trusted exchange, затем показать pairing.

При revoked/expired/invalid response persistent credential удаляется. Network error не удаляет его автоматически, но ограниченный retry не должен создавать цикл. Web shell не отображает token и не передаёт его в URL, cookie, DOM или telemetry.

Риск localStorage принят для LAN-only MVP с явным opt-in: web assets same-origin, без внешнего кода, пользовательский текст рендерится только как text, а CSP запрещает внешние scripts. Более сложный WebCrypto proof protocol отложен до security spike.

### 6. File draft получает собственную identity и source ownership

Android `FileSelectionItem` разделяется на draft model без `transferId` и queued transfer model. `AndroidFileSelectionPreparer` создаёт stable `draftId`, metadata/checksum и source lease. Для временных share URI private staged copy остаётся в `noBackupFilesDir`; удаление/clear draft освобождает lease и удаляет только staged copy, никогда не исходный document. При confirm coordinator выдаёт новые transfer ids принятым items и переносит ownership source lease в `FileSourceRegistry`. Rejected items остаются draft с validation error.

Повторный picker append-ит новые items. Dedupe key строится из доступной source identity и metadata; он предотвращает случайный двойной item в одном draft, не объявляя два разных файла одинаковыми только по имени. Cancel picker не отправляет action изменения selection.

В web `FileTransferController` хранит `DraftFile` со stable id и исходным `File`, предоставляет `addFiles`, `removeDraftItem` и `clearDraft`. Input value сбрасывается после обработки change, чтобы тот же файл можно было выбрать снова после удаления. Accepted offers удаляются из draft; rejected остаются с error. Refresh страницы очищает browser `File` references, что явно соответствует tab lifecycle.

### 7. Destination и effective limit читаются через settings use cases

`AndroidFileDestinationGateway` сначала проверяет сохранённый tree URI и persistable permission. UI incoming offer показывает текущую destination и позволяет применить её или открыть picker. Новый URI становится default только по отдельному user action; cancel picker не затирает прежнюю настройку.

`FileMetadataValidator` получает effective limit из `SettingsRepository` snapshot, всегда clamp-ит его к hard limit 1 ГиБ и применяет одно значение на Android selection и server offer validation. Авторизованный status/capability snapshot сообщает effective limit текущей session, чтобы web draft показывал раннюю ошибку; окончательное решение всегда остаётся за server.

Изменение лимита влияет только на новые drafts/offers. Active operation использует validation snapshot, с которым была принята, чтобы setting change не превращал выполняющуюся передачу в непредсказуемую ошибку.

### 8. Launcher icon остаётся repo-native Android resource

Template icon заменяется оригинальным простым знаком DeviceBridge: два устройства, соединённые коротким bridge/link, с крупными формами и safe-zone для системных масок. Foreground и monochrome создаются как VectorDrawable, background — локальный color resource; adaptive XML используется на поддерживаемых API, а raster/vector fallback проверяется на API 29. App label остаётся `DeviceBridge`.

Выбирается repo-native vector вместо удалённого изображения или bitmap-only набора: он детерминирован, не требует сети, масштабируется и позволяет отдельный monochrome layer. Проверка включает resource linking, preview разных masks и фактический launcher на API 29 и API 37.1.

### 9. Persistence исключается из Android backup

Room database, DataStore settings, trusted metadata/verifiers и private staged sources добавляются в `backup_rules.xml` и `data_extraction_rules.xml` exclusions. Пользовательские destination files не принадлежат приложению и не затрагиваются. Локальная история и trusted state не мигрируют скрыто на другое устройство.

## Risks / Trade-offs

- **[Trusted credential доступен same-origin script через localStorage]** → отсутствие внешних runtime assets, строгий text rendering/CSP, отдельный session token, 30-дневный TTL и немедленный revoke; повторная оценка в security spike.
- **[Room/IO может замедлить transfer callback]** → terminal-only async insert на IO dispatcher; progress и payload не ждут database.
- **[History write не удалась после успешной передачи]** → transfer result остаётся истинным, UI отдельно сообщает persistence error, idempotent operation id допускает безопасный повтор записи без повтора payload.
- **[SAF permission отозван системой или provider]** → проверка перед payload, очистка недоступной setting только после подтверждённой ошибки и возврат к picker.
- **[Staged share source может остаться после process death]** → private no-backup directory, ownership cleanup при remove/confirm terminal/server stop и sweep orphan files при следующем app start.
- **[Dedupe может ошибочно объединить разные files с одинаковыми metadata]** → использовать provider/browser identity hints вместе с metadata; dedupe ограничить текущим draft и позволить повторно добавить файл после удаления.
- **[OEM launcher иначе обрезает adaptive icon]** → держать основной знак внутри adaptive safe zone и вручную проверить несколько системных masks.
- **[Повреждённая database]** → storage failure изолируется от server/transfer lifecycle; History показывает recoverable error и действие очистки локальной database без удаления пользовательских files.

## Migration Plan

1. Добавить зафиксированные Room/DataStore зависимости и database version 1 без миграции существующих данных: текущая версия не имеет постоянной истории, settings или trusted records.
2. Ввести repositories/use cases и безопасные defaults, затем подключить terminal history recorder без изменения transfer protocol.
3. Подключить Settings/History UI, SAF destination и effective limit; обновить backup exclusions.
4. Ввести trusted DTO, repository/verifier, Android approval и server route; затем добавить отдельный web credential store и reconnect.
5. Перевести Android и web selection на draft ownership с cleanup и только после этого удалить старое раннее создание `transferId`.
6. Заменить launcher resources и проверить resource merge на API 29/API 37.1.
7. При rollback новая database/DataStore могут быть удалены вместе с app data; пользовательские files не изменяются, а отсутствие trusted route возвращает clients к обычному pairing.
