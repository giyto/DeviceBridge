## MODIFIED Requirements

### Requirement: Production server не раскрывает debug diagnostics и будущие API

Production lifecycle MUST раздавать встроенные web assets без внешнего backend и MUST публиковать только завершённые маршруты текущего этапа: public web manifest и pairing entry points, trusted-session exchange, защищённые session/status/text/file control routes, авторизованный events WebSocket, scoped upload/download streams, cancellation и explicit retry. Persistent history и обычные settings MUST оставаться локальными Android capabilities и MUST NOT публиковаться через LAN API.

#### Scenario: Browser открывает production endpoint
- **WHEN** пользователь открывает показанный LAN URL работающего production server
- **THEN** корневая страница, связанные assets и web manifest доступны по текущему публичному контракту
- **AND** страница не выполняет внешних запросов

#### Scenario: Запрошен диагностический маршрут
- **WHEN** клиент release-сборки запрашивает diagnostics route
- **THEN** сервер возвращает 404
- **AND** ответ не раскрывает диагностические сведения или token

#### Scenario: Запрошен реализованный защищённый маршрут
- **WHEN** client без действующей session запрашивает status, text, file control, cancellation или events route
- **THEN** server применяет session authorization contract
- **AND** не раскрывает приватные metadata или payload

#### Scenario: Авторизованный file route доступен
- **WHEN** действующая session создаёт допустимый file offer, upload, download grant, cancellation или explicit retry
- **THEN** server обрабатывает запрос по file protocol и ownership rules
- **AND** возвращает определённое state без публикации filesystem path

#### Scenario: Авторизованный text route доступен
- **WHEN** действующая browser session отправляет допустимый запрос на POST /api/v1/text
- **THEN** server обрабатывает его по text protocol и возвращает определённый результат
- **AND** Android host получает не более одного входящего элемента для одного messageId

#### Scenario: Trusted-session exchange доступен
- **WHEN** browser отправляет допустимый действующий trusted credential через определённую same-origin operation
- **THEN** server проверяет credential и создаёт только новую generation-scoped session
- **AND** credential не предоставляет доступ к другим маршрутам напрямую

#### Scenario: Запрошен локальный history или settings API
- **WHEN** client запрашивает history, Android settings или trusted-browser management route, не предназначенный для LAN
- **THEN** server возвращает 404 независимо от session token
- **AND** не выполняет пользовательскую операцию и не раскрывает локальные данные

#### Scenario: Запрошен будущий production API
- **WHEN** client запрашивает незавершённый или незаявленный route
- **THEN** server возвращает 404
- **AND** не расширяет доступ на основании browser label, IP или Host
