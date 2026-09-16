## ADDED Requirements

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
