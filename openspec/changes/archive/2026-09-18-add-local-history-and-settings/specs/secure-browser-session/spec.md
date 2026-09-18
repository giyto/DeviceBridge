## ADDED Requirements

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

## MODIFIED Requirements

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
