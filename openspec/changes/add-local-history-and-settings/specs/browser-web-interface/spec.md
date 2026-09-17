## ADDED Requirements

### Requirement: Browser file draft редактируется до upload

Web shell MUST хранить выбранные через file input или drag-and-drop files в отдельном draft до явного подтверждения. Пользователь MUST иметь возможность удалить отдельный item, очистить draft и добавить другие files; draft actions MUST NOT изменять исходные файлы компьютера и MUST NOT создавать server transfer.

#### Scenario: Удалён один выбранный файл
- **WHEN** пользователь активирует доступное действие удаления draft item
- **THEN** web UI удаляет только этот item из draft
- **AND** остальные выбранные items сохраняют порядок и validation result

#### Scenario: Добавлены другие файлы
- **WHEN** пользователь повторно выбирает или переносит допустимые files
- **THEN** web UI добавляет их к существующему draft
- **AND** одинаковый browser File не появляется повторно как неразличимый дубликат

#### Scenario: File dialog отменён
- **WHEN** пользователь закрывает повторно открытый file dialog без выбора
- **THEN** существующий draft остаётся без изменений
- **AND** upload автоматически не начинается

#### Scenario: Draft стал пустым
- **WHEN** пользователь очищает draft или удаляет последний item
- **THEN** действие подтверждения upload становится недоступным
- **AND** drop zone и file input остаются доступны для нового выбора

#### Scenario: Draft подтверждён
- **WHEN** пользователь подтверждает непустой валидный draft
- **THEN** каждый оставшийся item создаёт один file offer в стабильном порядке
- **AND** принятые items удаляются из draft, а отклонённые остаются с понятной ошибкой без повторного выбора

### Requirement: Browser может явно запросить доверенное подключение

Во время pairing web shell MUST предоставлять отдельную необязательную опцию «Запомнить этот браузер». Обычный session token MUST оставаться tab-scoped; только отдельный trusted credential, выданный после явного решения Android host, MAY храниться persistent для последующего обмена на новую session.

#### Scenario: Пользователь не запрашивает доверие
- **WHEN** pairing выполнен без опции «Запомнить этот браузер»
- **THEN** web shell хранит только текущий session token в session storage
- **AND** после завершения server generation новый pairing требует код

#### Scenario: Android разрешил доверие
- **WHEN** pairing завершён и Android host явно разрешил запомнить browser
- **THEN** web shell сохраняет отдельный opaque trusted credential в persistent browser storage
- **AND** не сохраняет generation-scoped session token как постоянный credential

#### Scenario: Trusted reconnect успешен
- **WHEN** страница открыта при новом server generation и сохранённый credential действителен
- **THEN** web shell обменивает его через same-origin trusted-session operation на новый tab-scoped session token
- **AND** открывает подключённый интерфейс без ввода нового pairing code

#### Scenario: Credential истёк или отозван
- **WHEN** trusted-session operation возвращает недействительный, истёкший или отозванный результат
- **THEN** web shell удаляет локальный credential и показывает обычную pairing form
- **AND** не повторяет запрос бесконечно и не сообщает ложное подключение
