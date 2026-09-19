## Purpose

Определить безопасный и проверяемый lifecycle авторизации browser client в локальной сети: одноразовый код, решение пользователя на Android host, session token, защита API и немедленный отзыв доступа.

## Requirements

### Requirement: Сервер выпускает ограниченный одноразовый код

При каждом успешном явном запуске production server DeviceBridge MUST создавать криптографически случайный шестизначный числовой pairing code. Код MUST действовать не более пяти минут, MUST отображаться только на Android host во время Running и MUST становиться недействительным после успешного pairing, истечения срока или завершения server lifecycle.

#### Scenario: Сервер начал новую сессию

- **WHEN** production server переходит в Running
- **THEN** Android host показывает новый шестизначный pairing code и оставшееся время его действия
- **AND** код не присутствует в URL, web manifest, web assets, уведомлении или журналах

#### Scenario: Код истёк

- **WHEN** срок действия активного pairing code заканчивается при работающем сервере
- **THEN** старый код больше не принимается
- **AND** Android host показывает новый код с новым сроком действия

#### Scenario: Код успешно использован

- **WHEN** пользователь разрешает pairing request, созданный с действующим кодом
- **THEN** использованный код немедленно становится недействительным
- **AND** следующий browser client должен использовать новый код

### Requirement: Неверные попытки pairing ограничиваются

DeviceBridge MUST принимать pairing code только через same-origin `POST /api/v1/session/confirm`, MUST проверять его без утечки ожидаемого значения и MUST временно блокировать новые попытки от источника после пяти неверных вводов. Начало pairing через `POST /api/v1/session/challenge` MUST возвращать только непривилегированный opaque challenge identifier и публичные параметры срока ожидания.

#### Scenario: Введён неверный код

- **WHEN** browser client отправляет неверный действующий по формату pairing code
- **THEN** сервер отклоняет попытку без создания session или запроса подтверждения
- **AND** сообщает безопасную ошибку и число оставшихся попыток без раскрытия ожидаемого кода

#### Scenario: Выполнена пятая неверная попытка

- **WHEN** один source IPv4 выполняет пятую неверную попытку в текущем окне ограничения
- **THEN** сервер блокирует новые попытки этого источника не менее чем на 60 секунд
- **AND** browser UI показывает отдельное состояние блокировки и оставшееся время

#### Scenario: Код истёк до отправки

- **WHEN** browser client отправляет ранее выданный, но уже истёкший код
- **THEN** сервер возвращает отдельную ошибку истечения без создания pending request
- **AND** попытка не может авторизовать browser session

### Requirement: Правильный код требует подтверждения на телефоне

Знание действующего pairing code MUST создавать только ограниченный по времени pending request и MUST NOT само по себе выдавать session token. Android host MUST показать отдельный запрос с browser label, source IPv4 и действиями разрешения и отказа; эти сведения MUST считаться информационными, а не доказательством личности клиента.

#### Scenario: Browser отправил правильный код

- **WHEN** browser client отправляет действующий код и допустимый browser label
- **THEN** web UI переходит в состояние ожидания, а Android host показывает соответствующий pending request
- **AND** защищённые API остаются недоступными этому browser client

#### Scenario: Пользователь разрешает подключение

- **WHEN** пользователь на Android host разрешает конкретный актуальный pending request
- **THEN** сервер создаёт одну browser session и возвращает token только запросившему клиенту
- **AND** Android и web UI показывают состояние «Подключено»

#### Scenario: Пользователь отклоняет или игнорирует подключение

- **WHEN** пользователь отклоняет pending request либо не отвечает до завершения ограниченного времени ожидания
- **THEN** сервер не создаёт session и не выдаёт token
- **AND** browser UI показывает различимое состояние отказа или истечения ожидания

#### Scenario: Решение относится к конкретному запросу

- **WHEN** одновременно существуют несколько pending requests или приходит повторное решение
- **THEN** решение применяется только к request с соответствующим opaque identifier
- **AND** один request не может создать более одной session

### Requirement: Session token является случайным и ограниченным lifecycle

После разрешения DeviceBridge MUST выпускать opaque bearer token с криптографической энтропией не менее 128 бит. Token MUST принадлежать конкретным server generation и browser session, MUST передаваться только в теле успешного same-origin ответа или авторизационном сообщении/заголовке и MUST NOT помещаться в URL, persistent Android storage, backup или журналы.

#### Scenario: Session разрешена

- **WHEN** Android host подтверждает pending request
- **THEN** server возвращает новый непредсказуемый token ровно один раз
- **AND** последующие HTTP-запросы передают его как `Authorization: Bearer <token>`

#### Scenario: Browser обновляет текущую вкладку

- **WHEN** авторизованный пользователь обновляет web page в той же browser tab
- **THEN** browser client может восстановить token только из tab-scoped session storage
- **AND** token отсутствует в local storage, cookie и URL

#### Scenario: Token относится к прошлому server generation

- **WHEN** клиент предъявляет token после остановки и нового явного запуска сервера
- **THEN** server отклоняет token как недействительный
- **AND** browser client должен пройти новый pairing

### Requirement: Production API закрыт session authorization

Production server MUST оставлять без token только `GET /`, разрешённые `/assets/*`, `GET /web-manifest.json`, `POST /api/v1/session/challenge` и `POST /api/v1/session/confirm`. Реализованные status и session routes MUST требовать действующий bearer token; WebSocket `/api/v1/events` MUST требовать token первым протокольным сообщением до отправки каких-либо приватных событий.

#### Scenario: Защищённый HTTP-запрос без token

- **WHEN** клиент запрашивает реализованный защищённый endpoint без token либо с неизвестным, отозванным или устаревшим token
- **THEN** сервер возвращает `401 Unauthorized`
- **AND** ответ не раскрывает состояние устройства, список sessions или другие приватные данные

#### Scenario: Авторизованный запрос статуса

- **WHEN** browser session запрашивает `GET /api/v1/status` с действующим bearer token и допустимыми Host/Origin
- **THEN** сервер возвращает совместимую версию протокола и фактическое состояние соединения
- **AND** не возвращает pairing codes или tokens других sessions

#### Scenario: WebSocket не авторизован

- **WHEN** клиент открывает `/api/v1/events`, но не отправляет допустимое auth message первым сообщением за ограниченное время
- **THEN** сервер закрывает соединение с определённой протокольной причиной
- **AND** не отправляет клиенту session events до закрытия

#### Scenario: WebSocket авторизован

- **WHEN** первым сообщением WebSocket переданы поддерживаемая `protocolVersion`, уникальный `messageId`, `type`, `timestamp` и действующий token
- **THEN** сервер связывает соединение с соответствующей browser session
- **AND** последующие события не требуют передачи token в URL

### Requirement: Пользователь управляет активными browser sessions

Android host MUST показывать фактическое число и список активных browser sessions и MUST позволять немедленно отозвать каждую session отдельно. Авторизованный browser client MUST иметь возможность завершить собственную session через защищённый `DELETE /api/v1/session`.

#### Scenario: Browser session подключена

- **WHEN** pairing успешно завершён и session остаётся действующей
- **THEN** Android UI и foreground notification показывают актуальное число подключённых браузеров
- **AND** Android UI показывает информационный label и время подключения session без раскрытия token

#### Scenario: Пользователь отзывает browser session

- **WHEN** пользователь выбирает отключение конкретного browser client на Android host
- **THEN** token этой session немедленно становится недействительным и её WebSocket закрывается
- **AND** browser UI показывает потерю авторизации и предлагает новый pairing

#### Scenario: Browser завершает собственную session

- **WHEN** авторизованный browser client выполняет `DELETE /api/v1/session`
- **THEN** сервер аннулирует только предъявившую token session
- **AND** другие browser sessions продолжают работать

#### Scenario: Server lifecycle завершается

- **WHEN** сервер остановлен пользователем, сеть потеряна, IPv4 изменён или локальное разрешение отозвано
- **THEN** все pending requests, active sessions и tokens текущего generation аннулируются
- **AND** ни одна session не восстанавливается автоматически после нового запуска

### Requirement: Session protocol не раскрывает секреты и не расширяет доверие

Session DTO и ошибки MUST иметь явную поддерживаемую protocol version, ограниченные размеры и безопасную сериализацию. DeviceBridge MUST NOT сохранять текущий session token как trusted-browser credential и MUST NOT считать browser label, User-Agent, IP или Host самостоятельным доказательством авторизации. Постоянное доверие MUST предоставляться только отдельным trusted credential по требованиям этого change.

#### Scenario: Получена неподдерживаемая версия протокола
- **WHEN** pairing, HTTP или WebSocket message содержит неподдерживаемую major protocol version
- **THEN** server отклоняет сообщение с понятным version error
- **AND** не создаёт и не изменяет session

#### Scenario: Получены лишние или слишком большие данные клиента
- **WHEN** challenge, confirm или trusted-session payload превышает лимит либо содержит недопустимые поля или значения
- **THEN** server отклоняет запрос до создания pending request, trusted record или session
- **AND** не отражает непроверенные данные как HTML в Android или web UI

#### Scenario: Browser просит постоянное доверие
- **WHEN** текущий web client запрашивает опцию «Запомнить этот браузер»
- **THEN** request становится частью конкретного pending pairing и требует отдельного разрешения Android host
- **AND** server не преобразует существующий session token в trusted credential

#### Scenario: Browser подделывает доверенные metadata
- **WHEN** client сообщает label, User-Agent, IP или trusted browser identifier без действующего секрета
- **THEN** server не создаёт session и не выдаёт credential
- **AND** информационные metadata не предоставляют дополнительных прав

### Requirement: Browser metadata нормализуется и не расширяет доверие

DeviceBridge MUST удалять внешние пробелы browser label, принимать только непустое значение длиной не более 64 символов без управляющих символов и отображать его только как plain text. Распознанная browser family и platform MUST оставаться информационными metadata и MUST NOT участвовать в выдаче token, авторизации API или выборе прав session.

#### Scenario: Получена корректная ограниченная метка

- **WHEN** pairing request содержит поддерживаемую browser family и безопасную platform label
- **THEN** server сохраняет нормализованную человекочитаемую метку для pending и active session
- **AND** Android отображает её только как информационные сведения

#### Scenario: Метка нарушает ограничения

- **WHEN** client присылает пустую browser label, управляющие символы или значение длиннее 64 символов
- **THEN** server отклоняет pairing payload до создания pending request
- **AND** непроверенное значение не попадает в UI или журнал и не влияет на авторизацию

#### Scenario: Метка похожа на HTML

- **WHEN** допустимая по размеру browser label содержит символы разметки
- **THEN** Android отображает всё значение как plain text
- **AND** содержимое не интерпретируется как разметка или команда

#### Scenario: Client подделывает название браузера

- **WHEN** client сообщает допустимую по формату, но недостоверную browser family
- **THEN** server всё равно требует pairing code, подтверждение Android host и действующий token
- **AND** метка не предоставляет дополнительных прав

### Requirement: File operations ограничены session ownership

Все file control routes, progress events, snapshots и cancellation MUST проверять действующую browser session и принадлежность transfer. Session MUST NOT читать metadata, скачивать, подтверждать или отменять item другого browser client.

#### Scenario: Session обращается к своему transfer

- **WHEN** авторизованный client выполняет допустимое действие над принадлежащим transferId
- **THEN** server применяет operation в границах этой session
- **AND** возвращает только относящееся к ней состояние

#### Scenario: Session обращается к чужому transfer

- **WHEN** авторизованный client указывает transferId другой session
- **THEN** server возвращает безопасный not-found или forbidden result
- **AND** не раскрывает имя, размер, checksum, progress или existence чужого item

### Requirement: Native download использует ограниченный одноразовый grant

Для запуска нативной browser download без bearer token в URL server MUST выпускать криптографически случайный одноразовый grant, привязанный к session, transferId и текущему server generation, с коротким сроком действия. Grant MUST NOT заменять session token и MUST быть непригоден для других API.

#### Scenario: Выдан download grant

- **WHEN** активная session запрашивает download для принадлежащего item
- **THEN** server выдаёт одноразовое разрешение только на этот stream
- **AND** session bearer token не помещается в URL

#### Scenario: Grant использован повторно

- **WHEN** client повторяет уже использованный grant
- **THEN** server отклоняет download
- **AND** не открывает второй source stream

#### Scenario: Session отозвана до download

- **WHEN** Android host отзывает session после выдачи grant
- **THEN** grant немедленно становится недействительным
- **AND** новый stream по нему не начинается

#### Scenario: Grant истёк

- **WHEN** download не начат до короткого срока действия
- **THEN** server отклоняет grant без file metadata
- **AND** browser предлагает запросить новое разрешение после проверки session

### Requirement: Trusted browser credential выдаётся только после явного решения Android

DeviceBridge MUST выдавать отдельный opaque trusted credential только во время успешно подтверждённого pairing, если browser запросил доверие и пользователь отдельно разрешил его на Android host. Credential MUST иметь не менее 128 бит криптографической энтропии, принадлежать одному browser identity record, истекать не позднее чем через 30 дней и MUST NOT быть текущим session token.

#### Scenario: Browser не запросил доверие
- **WHEN** пользователь подтверждает обычный pending pairing request
- **THEN** server выдаёт только generation-scoped session token
- **AND** trusted browser record не создаётся

#### Scenario: Browser запросил доверие
- **WHEN** pending request содержит допустимый trust request
- **THEN** Android явно показывает, что browser просит повторное подключение без кода
- **AND** обычное разрешение session и разрешение trust остаются различимыми решениями

#### Scenario: Android разрешил доверие
- **WHEN** пользователь разрешает session и отдельно разрешает запомнить browser
- **THEN** server выдаёт browser новый trusted credential и сохраняет защищённый verifier с metadata
- **AND** raw credential не отображается, не журналируется и не попадает в Android backup

### Requirement: Trusted credential создаёт только новую ограниченную session

Действующий trusted credential MUST позволять browser client получить новый generation-scoped session token без pairing code и нового pending approval. Trusted credential MUST использоваться только через same-origin trusted-session operation и MUST NOT самостоятельно авторизовать status, events или transfer routes.

#### Scenario: Trusted reconnect выполнен
- **WHEN** browser предъявляет действующий credential при работающем новом server generation
- **THEN** server создаёт новую browser session с нормализованной сохранённой label и новым session token
- **AND** transfer API продолжает принимать только новый session token

#### Scenario: Credential использован как bearer session token
- **WHEN** client передаёт trusted credential защищённому status, events, text или file route
- **THEN** server возвращает 401
- **AND** не раскрывает trusted browser metadata

#### Scenario: Credential истёк
- **WHEN** browser пытается восстановиться после срока действия trusted credential
- **THEN** server отклоняет reconnect без создания session
- **AND** browser должен удалить credential и пройти обычный pairing

### Requirement: Отзыв trusted browser немедленно прекращает доступ

Android host MUST позволять отозвать отдельный trusted credential или все credentials. Отзыв MUST запрещать последующие exchanges и завершать все active sessions, созданные из отозванного credential, не затрагивая остальные browser sessions.

#### Scenario: Отозван один trusted browser
- **WHEN** пользователь подтверждает отзыв конкретной trusted browser record
- **THEN** credential больше не проходит exchange, а производные active sessions и WebSockets закрываются
- **AND** другие trusted и ordinary sessions продолжают работать

#### Scenario: Отозваны все trusted browsers
- **WHEN** пользователь подтверждает глобальный отзыв
- **THEN** все trusted credentials и производные active sessions становятся недействительными
- **AND** новые подключения требуют обычного pairing

#### Scenario: Browser удалил credential локально
- **WHEN** пользователь очищает site data или отключает доверие в browser storage
- **THEN** server-side record не предоставляет доступ без предъявления секрета
- **AND** Android пользователь всё ещё может удалить оставшуюся metadata record

### Requirement: Pairing и session failures имеют различимые безопасные результаты

Session protocol SHALL возвращать стабильные безопасные причины для неверного формата или значения code, истёкшего code, ожидания Android approval, отказа или timeout approval, исчерпанного лимита attempts, превышения session capacity, отозванной session, истёкшей или отозванной trusted credential и неподдерживаемой protocol version. Ответ MUST NOT раскрывать правильный code, наличие конкретной trusted credential, session token или сведения другой session.

#### Scenario: Browser ожидает решение телефона

- **WHEN** правильный code принят и pairing request ожидает Android approval
- **THEN** browser получает состояние ожидания с ограниченным сроком
- **AND** повторный status request не создаёт новый pairing request

#### Scenario: Лимит попыток или capacity исчерпаны

- **WHEN** pairing заблокирован лимитом неверных attempts либо достигнут лимит активных sessions
- **THEN** browser получает различимую причину и допустимое время или действие для следующей попытки
- **AND** ответ не раскрывает identifiers других browsers

#### Scenario: Trusted reconnect отклонён

- **WHEN** trusted credential истекла или была отозвана
- **THEN** server отклоняет reconnect с terminal credential reason
- **AND** web shell удаляет только соответствующую локальную credential и предлагает обычный pairing

### Requirement: Неопределённый pairing result не подтверждается повторно автоматически

Потеря сети или timeout после отправки pairing request MUST переводить browser в uncertain state без автоматической повторной отправки code и без автоматического Android approval. Browser MAY проверить status исходного request в пределах срока жизни, а после terminal или неизвестного результата MUST вернуть пользователя к безопасному pairing step.

#### Scenario: Соединение потеряно после отправки code

- **WHEN** browser не получил terminal pairing result из-за разрыва соединения
- **THEN** UI показывает неопределённый результат и не отправляет code повторно автоматически
- **AND** пользователь не видит ложного connected state

#### Scenario: Исходный request всё ещё существует

- **WHEN** browser восстанавливает связь и server подтверждает pending request той же вкладки
- **THEN** browser продолжает отображать ожидание решения без создания второго Android prompt
- **AND** итоговое approval относится к исходному request
