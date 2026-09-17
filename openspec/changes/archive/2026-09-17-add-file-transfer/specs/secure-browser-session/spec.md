## ADDED Requirements

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
