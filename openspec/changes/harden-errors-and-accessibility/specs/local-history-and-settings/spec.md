## ADDED Requirements

### Requirement: History и Settings различают loading, empty и persistence error

History и Settings MUST показывать initial loading до первого результата persistence, отдельное empty state при успешном пустом результате и error state при неуспешном чтении. Ошибка чтения или записи MUST NOT подменяться пустым списком, default success либо вымышленными данными.

#### Scenario: History успешно пуста

- **WHEN** repository завершил загрузку и не нашёл terminal records
- **THEN** History показывает подтверждённое empty state
- **AND** retry не отображается как обязательное действие

#### Scenario: History не удалось загрузить

- **WHEN** persistence возвращает ошибку чтения
- **THEN** History показывает error state и явный retry
- **AND** не сообщает пользователю, что операций ещё не было

#### Scenario: Settings не удалось сохранить

- **WHEN** пользователь подтверждает допустимое значение, но persistence не завершает запись
- **THEN** UI сообщает об ошибке, сохраняет последнее успешно persisted значение как активное и оставляет введённый draft для исправления или retry
- **AND** не показывает ложное уведомление об успешном сохранении

### Requirement: Недоступный SAF destination восстанавливается без потери других данных

При отзыве persistable permission, удалении provider или недоступности выбранной SAF-папки DeviceBridge MUST обозначить destination как недоступный, прекратить использовать его для новых записей и предложить выбрать папку снова. Ошибка destination MUST NOT удалять history, trusted browsers, другие settings или независимые file drafts.

#### Scenario: Persistable permission отозвано

- **WHEN** приложение больше не может открыть сохранённый tree URI
- **THEN** Settings показывает недоступный destination и действие «Выбрать папку»
- **AND** browser upload ожидает нового destination вместо записи в неразрешённое место

#### Scenario: Повторный picker отменён

- **WHEN** пользователь закрывает folder picker без выбора
- **THEN** прежний destination остаётся помеченным недоступным, а pending offer сохраняется, если source и session ещё действуют
- **AND** history и остальные settings не изменяются

#### Scenario: Новая папка успешно выбрана

- **WHEN** пользователь выдаёт persistable access к новому tree URI
- **THEN** DeviceBridge валидирует доступ и только после этого делает папку активной
- **AND** pending item может быть продолжен явным действием пользователя без создания дубликата

### Requirement: History и Settings визуально поддерживают быстрое сканирование

History MUST использовать спокойную плотность списка и различать direction, type и status без избыточных вложенных decorative cards. Empty state MUST кратко объяснять отсутствие данных и показывать не более одного полезного primary action, если оно применимо. Settings MUST группировать поля в логические sections, показывать validation рядом с проблемным control и визуально различать draft, saving, saved и failed save.

#### Scenario: History содержит разные операции

- **WHEN** список содержит text и file records разных направлений и статусов
- **THEN** пользователь может различить основные metadata без открытия каждой записи
- **AND** status передаётся текстом и семантикой, а не только цветом или иконкой

#### Scenario: History пуста

- **WHEN** подтверждённый repository result не содержит records
- **THEN** empty state показывает короткое объяснение без фиктивных данных
- **AND** предлагает одно релевантное действие либо не показывает action, если оно не требуется

#### Scenario: Settings save не завершён

- **WHEN** допустимый draft сохраняется либо запись завершилась ошибкой
- **THEN** control и section показывают saving или failed state рядом с изменённым значением
- **AND** весь экран не блокируется общей modal error без необходимости
