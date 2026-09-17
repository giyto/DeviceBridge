## MODIFIED Requirements

### Requirement: Текущая text-лента не становится постоянной историей

Android и web UI MUST показывать отправленные и полученные text items текущего server generation с отправителем, временем, направлением, типом и status. Полное содержимое и текущая лента MUST NOT сохраняться постоянно. После terminal результата Android MUST создать не более одной local history record с безопасными metadata и plain-text preview не длиннее 200 Unicode code points; trusted-browser state и Android backup MUST NOT содержать полный text payload.

#### Scenario: Пользователь просматривает текущую сессию
- **WHEN** в текущем server generation были переданы элементы
- **THEN** Android и соответствующая browser session показывают доступные им полные элементы и статусы
- **AND** каждый элемент различает направление и отправителя

#### Scenario: Browser восстанавливает ту же session после обновления страницы
- **WHEN** страница обновлена в той же вкладке и tab-scoped token остаётся действительным
- **THEN** после повторной авторизации events connection web UI получает ограниченный snapshot доступной текущей text-ленты
- **AND** элементы с уже известным messageId не дублируются

#### Scenario: Text operation получила terminal result
- **WHEN** операция получает delivered или failed
- **THEN** Android history сохраняет messageId, тип, направление, browser label, время, status и безопасный короткий preview
- **AND** повторный acknowledgement или идемпотентный request не создаёт вторую record

#### Scenario: Server lifecycle завершён
- **WHEN** пользователь останавливает сервер или generation завершается из-за ошибки сети или разрешения
- **THEN** незавершённые операции получают конечное состояние ошибки или отмены согласно transfer contract
- **AND** новый server generation не восстанавливает полное содержимое прежней text-ленты, но terminal history metadata остаются доступными

#### Scenario: History storage недоступен
- **WHEN** terminal text operation не удалось записать в local history
- **THEN** фактический delivery result не изменяется и payload повторно не отправляется
- **AND** Android показывает отдельную безопасную ошибку persistence без раскрытия полного текста в журнале
