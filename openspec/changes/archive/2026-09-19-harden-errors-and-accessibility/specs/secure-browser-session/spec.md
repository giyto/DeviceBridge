## ADDED Requirements

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

