## ADDED Requirements

### Requirement: Android shell предоставляет file flow для выбранной session

Android-приложение MUST позволять выбрать один или несколько файлов, показать recipient и metadata, подтвердить отправку и наблюдать очередь текущего server generation.

#### Scenario: Активна одна browser session

- **WHEN** пользователь открывает действие «Файлы» и выбирает документы
- **THEN** Android показывает выбранную session, имена и размеры файлов
- **AND** не начинает передачу до явного подтверждения

#### Scenario: Активно несколько browser sessions

- **WHEN** пользователь готовит Android → Browser transfer
- **THEN** Android требует явно выбрать одну session
- **AND** не выполняет скрытую рассылку всем browsers

#### Scenario: Отображаются active transfers

- **WHEN** queue содержит queued, active или terminal items текущего generation
- **THEN** Android показывает отдельные карточки с направлением, progress и доступным действием отмены или повтора
- **AND** карточки не подменяют состояние text flow

### Requirement: Android принимает системный share только в preview

DeviceBridge MUST принимать один или несколько content URI через ACTION_SEND и ACTION_SEND_MULTIPLE, но MUST NOT начинать передачу до проверки metadata, выбора активной session и подтверждения пользователя.

#### Scenario: Другой Android app делится одним файлом

- **WHEN** DeviceBridge получает ACTION_SEND с доступным content URI
- **THEN** приложение открывает file preview
- **AND** требует выбрать recipient и подтвердить отправку

#### Scenario: Другой Android app делится несколькими файлами

- **WHEN** DeviceBridge получает ACTION_SEND_MULTIPLE с доступными content URI
- **THEN** приложение показывает каждый допустимый item
- **AND** недоступный URI отмечается ошибкой без crash всего preview

### Requirement: Android управляет destination через системный picker

Для Browser → Android transfer приложение MUST запросить у пользователя destination через Storage Access Framework и MUST предоставлять явное действие открытия успешно сохранённого файла. Широкое storage permission MUST NOT требоваться.

#### Scenario: Получено file offer

- **WHEN** browser предлагает допустимые файлы
- **THEN** Android показывает sender, имена, размеры и действие выбора папки
- **AND** transfer не принимается скрыто в неизвестное location

#### Scenario: Файл успешно сохранён

- **WHEN** transfer завершён и checksum совпал
- **THEN** Android показывает completed и действие открытия файла
- **AND** передаёт внешнему viewer только scoped content URI permission

## MODIFIED Requirements

### Requirement: Недоступность передачи без активной сессии

Android-приложение MUST блокировать transfer actions без работающего сервера и активной browser session. После подключения действия «Текст» и «Файлы» MUST быть доступны в рамках реализованных capabilities.

#### Scenario: Действия при остановленном сервере

- **WHEN** пользователь находится на главном экране в состоянии «Сервер остановлен»
- **THEN** действия «Текст» и «Файлы» отображаются недоступными
- **AND** интерфейс сообщает, что сначала потребуется запустить сервер и подключить браузер

#### Scenario: Сервер запущен без авторизованного браузера

- **WHEN** production server работает, но ни один browser client не авторизован
- **THEN** действия «Текст» и «Файлы» остаются недоступными
- **AND** интерфейс предлагает завершить pairing с браузером

#### Scenario: Browser авторизован до реализации transfer

- **WHEN** хотя бы одна browser session активна
- **THEN** действия «Текст» и «Файлы» доступны и открывают соответствующий flow
- **AND** каждый flow требует явного recipient и подтверждения пользовательской операции
