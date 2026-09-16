## ADDED Requirements

### Requirement: Web shell предоставляет file upload flow

После authorization web shell MUST позволять выбрать несколько файлов через системный input или drag-and-drop, показать metadata до отправки и наблюдать очередь, progress, cancellation и результат.

#### Scenario: Файлы выбраны через input

- **WHEN** пользователь выбирает один или несколько файлов
- **THEN** web UI показывает имя, размер и validation result каждого item
- **AND** upload начинается только после явного подтверждения

#### Scenario: Файлы добавлены drag-and-drop

- **WHEN** пользователь переносит files в доступную drop zone
- **THEN** система применяет те же validation и confirmation, что для input
- **AND** drop zone имеет доступную клавиатурную альтернативу

#### Scenario: Upload отменён

- **WHEN** пользователь нажимает отмену queued или active item
- **THEN** web UI отправляет cancellation и показывает terminal state
- **AND** не сообщает completed по локальному событию завершения request без server result

### Requirement: Web shell предоставляет проверяемый download flow

Входящее Android → Browser предложение MUST показывать sender, имя, размер и действия скачивания и отклонения. После нативной загрузки web UI MUST предоставить выбор скачанного файла для потоковой SHA-256 verification и MUST различать transferring, verifying и completed.

#### Scenario: Пользователь начинает download

- **WHEN** пользователь принимает доступное предложение
- **THEN** browser начинает загрузку по одноразовому разрешению текущей session
- **AND** page продолжает показывать server progress без хранения полного payload

#### Scenario: Требуется проверка

- **WHEN** download stream передан полностью
- **THEN** web UI просит явно выбрать сохранённый файл
- **AND** объясняет, что это ограничение безопасной проверки на локальном HTTP

#### Scenario: Checksum совпал

- **WHEN** выбранный файл имеет ожидаемые size и SHA-256
- **THEN** web UI показывает completed
- **AND** результат отправляется server как acknowledgement текущей operation

### Requirement: File controls доступны и адаптивны

File input, drop zone, queue cards, progress, cancel, retry и verify controls MUST оставаться доступными с клавиатуры, экранного диктора и при ширине viewport от 360 до 1920 пикселей в светлой и тёмной теме.

#### Scenario: Управление с клавиатуры

- **WHEN** пользователь проходит file flow клавишей Tab
- **THEN** все обязательные действия имеют видимый focus и понятное accessible name
- **AND** progress и terminal status доступны без опоры только на цвет

#### Scenario: Узкое окно

- **WHEN** viewport имеет ширину 360 пикселей
- **THEN** имя, progress и основное действие не перекрываются и остаются доступными
- **AND** горизонтальная прокрутка страницы не требуется для выполнения transfer

## MODIFIED Requirements

### Requirement: Недоступные функции не имитируют готовую работу

После session authorization web shell MUST позволять отправлять и получать текст и файлы через реализованные capabilities. Интерфейс MUST различать доступность local server, authorization, text connection, file queue и terminal результаты.

#### Scenario: Пользователь открывает web shell третьего этапа

- **WHEN** локальный server доступен, но browser client ещё не авторизован
- **THEN** пользователь видит pairing form, а text и file actions недоступны
- **AND** HTTP availability не обозначается как авторизованная session

#### Scenario: Browser успешно подключён

- **WHEN** pairing завершён, session token действителен и events connection готов
- **THEN** web shell показывает text form, file upload controls и входящие offers
- **AND** ни одна пользовательская операция не запускается автоматически

#### Scenario: File capability временно недоступна

- **WHEN** session потеряла events connection или server отклонил file protocol
- **THEN** web shell не имитирует queued, delivered или completed
- **AND** показывает понятную ошибку и безопасное действие восстановления

#### Scenario: Text capability временно недоступна

- **WHEN** авторизованная session потеряла events connection или получила text protocol error
- **THEN** web shell не имитирует успешную отправку текста
- **AND** показывает понятную ошибку и действие восстановления соединения
