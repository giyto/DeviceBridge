## ADDED Requirements

### Requirement: Recoverable text failure сохраняет draft и идентичность операции

Если text или link operation не получила terminal acknowledgement из-за временного разрыва, UI MUST сохранить исходный draft и обозначить результат как uncertain или failed, а не delivered. Manual retry той же операции MUST повторно использовать исходный `messageId`; изменение содержимого MUST считаться новой операцией с новым identifier.

#### Scenario: Связь потеряна до acknowledgement

- **WHEN** отправитель передал text payload, но не получил terminal acknowledgement до разрыва session
- **THEN** item остаётся uncertain или failed и предоставляет retry только после восстановления session
- **AND** исходный текст остаётся доступен пользователю в текущем server generation

#### Scenario: Пользователь повторяет без изменения содержимого

- **WHEN** пользователь явно повторяет ту же text operation после восстановления session
- **THEN** отправитель использует исходный `messageId`
- **AND** уже принявший payload получатель возвращает прежний результат без второй записи в ленте

#### Scenario: Пользователь редактирует draft перед отправкой

- **WHEN** пользователь меняет содержимое uncertain или failed item и подтверждает отправку
- **THEN** DeviceBridge создаёт новую operation с новым `messageId`
- **AND** предыдущий item сохраняет собственный terminal или uncertain status

### Requirement: Text recovery action соответствует причине ошибки

Text UI MUST различать потерю session, превышение размера, пустой payload, неподдерживаемую ссылку и неподдерживаемую protocol version. Retry SHALL быть доступен только для причины, при которой повтор неизменённой операции может завершиться успешно.

#### Scenario: Payload превышает лимит

- **WHEN** текст больше установленного protocol limit
- **THEN** UI показывает лимит и предлагает сократить содержимое
- **AND** не показывает сетевой retry для неизменённого payload

#### Scenario: Session потеряна

- **WHEN** выбранная browser session закрыта до отправки
- **THEN** UI сохраняет draft и предлагает выбрать или восстановить получателя
- **AND** payload не перенаправляется другой session автоматически

### Requirement: Text session feed остаётся читаемой и управляемой

Android и web UI MUST представлять текущие text/link items в спокойной session feed с различимыми направлением, отправителем, временем, типом и status. Composer MUST оставаться в предсказуемой доступной области. Item actions MUST находиться внутри соответствующей карточки и MUST NOT перекрывать текст, ссылку или соседние controls при длинном содержимом, font scale или browser zoom.

#### Scenario: Длинная ссылка получена на Android

- **WHEN** link item содержит URL, который шире доступной карточки
- **THEN** отображение переносится или сокращается без изменения копируемого значения
- **AND** действия «Копировать» и «Открыть» остаются внутри карточки и доступны TalkBack

#### Scenario: Browser открыт с zoom 200 процентов

- **WHEN** text feed отображается при zoom 200 процентов или viewport 360 пикселей
- **THEN** composer, item status и actions остаются доступны без горизонтальной прокрутки страницы
- **AND** visual order совпадает с keyboard focus order

#### Scenario: Item ожидает доставку

- **WHEN** text operation находится в sending или uncertain state
- **THEN** карточка показывает текстовый status и только применимые actions без ложного success styling
- **AND** изменение status не перемещает focus пользователя автоматически
