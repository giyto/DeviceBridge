## Context

См. proposal.md — Why. Текущий production stack уже имеет Ktor 3.5.2/CIO, foreground service, active browser sessions, защищённые HTTP/WebSocket routes и двусторонний text protocol. Диагностический spike подтвердил на API 29 и API 37.1 потоковые upload/download по 500 МБ, cancellation, SHA-256 и запас по памяти; production file routes пока намеренно возвращают 404.

ТЗ 2.0 требует одно Android-приложение, LAN HTTP/WebSocket, отсутствие desktop companion и service worker, передачу в обе стороны, одну активную сетевую операцию каждого направления, SAF, progress и cancellation. Browser → Android payload проверяется по SHA-256; Android → Browser завершается после успешной потоковой отдачи заявленного количества байтов без повторного выбора скачанного файла. Для текущего change зафиксирован диапазон одного файла от 0 байт до 1 ГиБ включительно и обязательная 500 МБ проверка потоковой передачи. История, постоянная папка назначения, уменьшаемая пользователем file-size setting и trusted browsers относятся к следующему change.

Web stack: TypeScript 7.0.2, Vite 8.3.0, Vitest 5.0.0 без UI framework. Android stack: Kotlin 2.2.10, Compose, Hilt 2.59.2, Coroutines 1.10.2 и Ktor 3.5.2 в одном app module.

Ключевое ограничение: File System Access API и showSaveFilePicker доступны только в secure context, тогда как DeviceBridge открывается по обычному LAN HTTP. Chrome также указывает, что полный polyfill невозможен, а fallback через download link не даёт странице writable handle. Поэтому проект не может честно обещать прямую потоковую запись web page в выбранный Windows-файл. Источники: [Chrome File System Access](https://developer.chrome.com/docs/capabilities/web-apis/file-system-access), [MDN showSaveFilePicker](https://developer.mozilla.org/en-US/docs/Web/API/Window/showSaveFilePicker).

## Goals / Non-Goals

**Goals:**

- сохранить Clean Architecture, MVVM, SOLID и один process-wide source of truth для transfer state;
- обрабатывать payload от 0 байт до hard limit 1 ГиБ ограниченными chunks, обязательно проверять 500 МБ и не блокировать main/UI thread;
- дать обоим направлениям одинаковые terminal semantics, ownership, progress и cancellation, сохранив SHA-256 для Browser → Android payload;
- использовать только user-granted content URI на Android и нативный download manager браузера на LAN HTTP;
- обеспечить тестируемый protocol и воспроизводимую API 29/API 37.1 + Chrome/Edge матрицу.

**Non-Goals:**

- Room history, DataStore settings, постоянная default folder и trusted-browser credentials;
- resumable/range transfer, автоматический retry и продолжение операции после нового server generation;
- background download без открытой browser tab, PWA/service worker, HTTPS или desktop companion;
- preview содержимого файлов, antivirus scanning, архивирование или преобразование форматов;
- поддержка Safari/Firefox как обязательная acceptance matrix.

## Decisions

### 1. Control plane отделён от binary stream

File protocol использует JSON control messages и WebSocket events для metadata/state, а содержимое каждого файла передаётся отдельным HTTP stream. Один file item соответствует одному transferId; multiple selection создаёт ordered batch отдельных items.

Hard limit одного item задаётся общей protocol constant 1 073 741 824 bytes. Android и web выполняют ту же preflight-проверку для быстрого UX, но server повторно проверяет заявленный и фактический размер. Нулевой файл допустим и использует стандартный SHA-256 пустого содержимого. Этап настроек сможет задать только меньший runtime limit; повышение hard limit требует нового performance change.

Browser → Android:

1. POST /api/v1/files создаёт idempotent offer с массивом metadata.
2. Android пользователь подтверждает offer и выбирает destination.
3. Event file.readyForUpload разрешает конкретный item.
4. POST /api/v1/files/{transferId} передаёт raw binary body с Authorization, Content-Length и заявленным SHA-256.

Android → Browser:

1. Android создаёт offers после выбора recipient и files.
2. Browser запрашивает POST /api/v1/files/{transferId}/download-grant.
3. Нативный download выполняет GET /api/v1/files/{transferId} с одноразовым grant.
4. После успешной отдачи заявленного количества байтов server переводит item в completed и запускает следующий queued item направления.

DELETE /api/v1/transfers/{transferId} отменяет queued или active operation. Все control commands и events имеют protocolVersion, messageId, timestamp и idempotency semantics, совместимые с text protocol.

POST /api/v1/transfers/{transferId}/retry выполняет только явный повтор принадлежащего session terminal item. Команда возвращает тот же item в очередь с обнулённым progress; payload не запускается автоматически. Для Android → Browser повтор доступен, пока Android сохраняет исходный content URI или private staged source текущего server generation.

Альтернатива — multipart batch. Она отклонена: один большой multipart усложняет независимую очередь, progress, cancellation и cleanup отдельных файлов.

### 2. Очередь принадлежит server generation

Новый FileTransferRepository определяет domain operations и StateFlow snapshot. FileTransferCoordinator в data layer владеет двумя FIFO queues и допускает максимум один active item Android → Browser и один Browser → Android глобально для текущего server generation. Text coordinator работает независимо.

State machine:

queued → connecting → transferring → completed

Промежуточный verifying сохраняется только там, где принимающая сторона внутри DeviceBridge действительно вычисляет SHA-256, прежде всего для Browser → Android upload. Android → Browser не ожидает ручного post-download acknowledgement.

Из любого non-terminal state разрешены cancelled или failed. Terminal state неизменяем внутри reducer; только отдельная явная retry-команда scheduler создаёт новую попытку того же item из сохранённых metadata и сбрасывает progress. Reducer проверяет монотонность bytes, допустимые переходы и ownership. Queue и terminal metadata ограничены по количеству, очищаются при server stop и не записываются в Room.

Альтернатива — отдельная очередь на browser session. Она отклонена, поскольку несколько sessions смогли бы параллельно перегрузить один телефон и нарушить FR-06.

### 3. Один per-session outbound event actor

Текущий socket event path обобщается из text-only hub в единый per-session event dispatcher. Text, file offers, progress, snapshots и terminal acknowledgements сериализуются одним bounded actor на session; slow consumer не создаёт неограниченную очередь. Progress coalesces/throttles, а control/terminal events не теряются.

Это сохраняет один авторизованный WebSocket и исключает независимые writers в один channel. Session revoke сначала запрещает новые operations, затем отменяет её transfers и закрывает socket.

### 4. Browser → Android использует XHR для body progress и SAF для destination

Web client сначала инкрементально читает File.stream() и вычисляет source SHA-256, затем отправляет исходный File как raw XHR body. XMLHttpRequestUpload предоставляет стабильные progress/abort events; File/Blob stream отдаёт chunks без полного ArrayBuffer. Источники: [XMLHttpRequest upload progress](https://developer.mozilla.org/en-US/docs/Web/API/XMLHttpRequest/upload), [WHATWG XHR progress](https://xhr.spec.whatwg.org/), [Blob.stream](https://developer.mozilla.org/en-US/docs/Web/API/Blob/stream).

Android показывает incoming offer и запускает ACTION_OPEN_DOCUMENT_TREE для папки текущего batch. SAF даёт доступ только к выбранным document URI без storage permission; Android documentation прямо определяет ACTION_OPEN_DOCUMENT, ACTION_CREATE_DOCUMENT и ACTION_OPEN_DOCUMENT_TREE для user-controlled доступа. Источник: [Android Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files).

После approval coordinator создаёт уникальный partial document, пишет request channel chunks в ContentResolver output, одновременно обновляет MessageDigest и progress. При совпадении size/SHA document становится completed; при cancel/error/mismatch он удаляется. Если provider не позволяет delete/rename, item остаётся failed с явным partial marker и URI для ручной очистки.

Для picker URI берётся временный/persistable read grant только на срок нужных operations и затем освобождается. Для share URI, которые нельзя persist, source descriptor открывается при подтверждении; если grant недостаточен для queued lifetime, содержимое потоково staging-ится в no-backup internal storage и удаляется в terminal cleanup. Полный payload никогда не хранится в памяти.

### 5. Android → Browser использует native download без повторного выбора файла

Android selection использует OpenMultipleDocuments; ACTION_SEND и ACTION_SEND_MULTIPLE открывают тот же preview без auto-send. Android guidance требует дать пользователю возможность подтвердить shared content и документирует оба share actions: [Receive shared content](https://developer.android.com/develop/ui/compose/sharing/receive).

Coordinator делает первый потоковый pass по source URI для size/SHA-256, затем предлагает item session. После user click server выдаёт random 128-bit one-time grant, scoped к session + transferId + generation, с TTL 30 секунд. Grant можно использовать один раз; revoke/stop инвалидирует его. Session bearer token никогда не попадает в URL. Raw grant исключается из application logs, response задаёт Referrer-Policy: no-referrer и безопасный Content-Disposition.

Ktor отдаёт ContentResolver InputStream через respondOutputStream/ByteWriteChannel с content length и считает фактически записанные bytes. Ktor 3.5 поддерживает incremental request/response I/O, не требующее полного payload: [Ktor I/O interoperability](https://ktor.io/docs/io-interoperability.html), [respondOutputStream API](https://api.ktor.io/ktor-server-core/io.ktor.server.response/respond-output-stream.html).

После server-side stream completion item сразу становится completed. Критерий успеха — source stream закончился ровно на заявленном размере, response не был прерван и все байты были переданы в native download response. Web UI не просит повторно выбирать сохранённый файл и не удерживает очередь в verifying.

При пользовательской отмене или сетевой ошибке source URI/private staged source не удаляется немедленно: он остаётся привязанным к terminal item для explicit retry в текущем server generation. Источник удаляется после completed, при revoke владельца или общем server stop. Retry снова ставит тот же item в FIFO и требует нового действия «Скачать», поэтому отмена не теряет файл и не запускает скрытый повтор.

Trade-off: на обычном LAN HTTP web page не может подтвердить фактическую запись файла на диск после нативной загрузки. UI честно сообщает только о завершении передачи сервером; пользователь при необходимости проверяет сохранённый файл средствами ОС. Chromium integration test обязан доказать, что one-time download handoff не буферизует payload и работает в Chrome/Edge.

### 6. SHA-256 абстрагирован от платформы

Domain видит digest value и verification result для потоков, где DeviceBridge контролирует принимающую сторону. Android adapter использует java.security.MessageDigest по chunks на Dispatchers.IO. Web получает StreamingSha256 interface для подготовки Browser → Android upload; реализация поставляется внутри bundled assets, не делает внешних запросов и проверяется standard vectors, boundary chunks и 500 МБ deterministic fixture.

Web Crypto digest не выбирается для больших файлов, потому что его one-shot input потребовал бы полный ArrayBuffer. Конкретная pure TypeScript или bundled WASM реализация выбирается после license/size/security benchmark, не меняя protocol или tasks.

### 7. Безопасные имена и collision policy применяются до открытия output

Filename normalizer извлекает leaf name, удаляет separators/control/bidi-dangerous characters, ограничивает длину, сохраняет безопасное extension и использует fallback file-<short-id>. MIME остаётся недоверенным display hint.

Destination resolver проверяет существующие children и создаёт видимое уникальное имя вида name (1).ext. Скрытая перезапись запрещена. Filesystem path, content URI и provider details не уходят в browser events.

### 8. Lifecycle, Wi-Fi lock и cleanup объединены с foreground service

File coordinator регистрируется в server generation рядом с session/text coordinators. Stop order:

1. запретить новые offers/grants;
2. отменить queued/active jobs и invalidates grants;
3. закрыть request/response channels, descriptors и outputs;
4. удалить/пометить partial artifacts;
5. закрыть sessions/listener;
6. освободить Wi-Fi lock и notification.

Wi-Fi lock acquire выполняется reference-counted только при первом state transferring и release после последнего transferring либо общего cleanup. Hash-only verifying и queued state lock не удерживают.

Notification получает sanitised filename, направление и progress; content, URI, checksum и token не показываются.

### 9. UI следует существующему MVVM и общему state

FileScreen/ViewModel зависят от use cases и FileTransferRepository, а не от Ktor, ContentResolver или service. Home quick action открывает flow только при active session. При нескольких sessions recipient выбирается явно.

Web file controller отделяет DOM rendering от API/XHR/hash adapters. Drop event и input используют один validation path. Refresh восстанавливает bounded snapshot только для текущей session; оборванный upload/download не повторяется автоматически.

### 10. Verification gates обязательны до ручной приёмки

- Domain/unit: reducer, queue ordering, concurrency, 0-byte/1-GiB boundaries, filename normalization, collision, idempotency, checksum и cleanup.
- Server integration: authorization/ownership, raw streaming, one-time grant, cancellation, disconnect, size mismatch, checksum mismatch, text during file transfer.
- Web/Vitest: selection, drag-and-drop, XHR progress/abort, download handoff, отсутствие post-download picker, hash vectors для upload, refresh snapshot и accessibility contracts.
- Compose/instrumented: picker gateways, ACTION_SEND/ACTION_SEND_MULTIPLE preview, destination denial, progress/cancel/open actions и notification.
- API 29/API 37.1: 500 МБ upload/download, memory bounds, cancellation recovery, 20 start/stop cycles и descriptor/Wi-Fi-lock cleanup.
- Chrome/Edge on Windows: single/multiple files, 500 МБ native download без повторного выбора, upload, cancellation, refresh and LAN HTTP.

Playwright может быть добавлен как pinned dev dependency только для Chromium integration fixture; production web bundle не получает runtime dependency или внешнюю сеть.

## Risks / Trade-offs

- [Native download не сообщает странице локальный disk completion] → completed означает успешную отдачу полного response сервером, а не подтверждённую запись на диск; повторный выбор файла намеренно отсутствует.
- [Download grant присутствует в request URL] → отдельный random single-use grant на 30 секунд, no-referrer, отсутствие request-query logging, ownership check и немедленная invalidation.
- [Provider не поддерживает rename/delete partial document] → создавать уникальный temporary display name, попытаться delete, иначе показать failed partial item и инструкцию очистки; никогда не отмечать completed.
- [Двойной pass для source hash увеличивает время и I/O] → вычислять на background dispatcher, показывать preparation/verifying phase и сохранять bounded memory; correctness важнее скрытой задержки.
- [Share URI permission может быть временным] → persist grant когда provider разрешает, иначе держать descriptor либо потоково stage в no-backup storage с гарантированным cleanup.
- [Progress events перегружают socket/UI] → coalescing по времени/bytes и bounded actor; terminal events имеют приоритет.
- [Incremental web SHA implementation ошибочна или слишком велика] → standard vectors, cross-check с Android/JDK, license and bundle-size gate, deterministic 500 МБ fixture.
- [Два одновременных направления конкурируют за Wi-Fi/storage] → максимум один stream каждого направления, memory/performance instrumentation и возможность отмены.

## Migration Plan

1. Добавить protocol/domain models, reducers и repository contracts без публикации routes.
2. Реализовать coordinator, SAF/storage adapters, cleanup и tests за закрытым composition wiring.
3. Подключить защищённые routes/events и оставить production route policy deny-by-default до прохождения security tests.
4. Добавить Android и web flows, затем Chromium native-download/hash gate.
5. Выполнить automated suites и device/browser matrix; только после 500 МБ, cancellation и cleanup acceptance открыть file routes как production capability.
6. Rollback выполняется revert change: file routes снова 404, text/session/lifecycle остаются совместимыми, Room/DataStore migration отсутствует.
