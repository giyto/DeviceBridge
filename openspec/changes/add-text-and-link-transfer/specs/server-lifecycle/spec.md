## MODIFIED Requirements

### Requirement: Уведомление обеспечивает видимость и остановку сервера

При работающем сервере DeviceBridge MUST показывать постоянное foreground-уведомление с состоянием, локальным endpoint, фактическим числом подключённых браузеров и состоянием активной text transfer. Уведомление MUST содержать действие «Остановить».

#### Scenario: Сервер успешно запущен

- **WHEN** server lifecycle переходит в состояние «Сервер запущен»
- **THEN** уведомление показывает доступный локальный адрес и актуальное число подключённых браузеров
- **AND** без активной операции не показывает фиктивную передачу

#### Scenario: Выполняется text transfer

- **WHEN** исходящий text item ожидает отправки или acknowledgement
- **THEN** уведомление показывает безопасный статус активной передачи без её содержимого
- **AND** после результата возвращается к обычному server status

#### Scenario: Остановка из уведомления

- **WHEN** пользователь нажимает «Остановить» в уведомлении
- **THEN** система останавливает сервер, завершает незавершённые text operations и закрывает соединения
- **AND** удаляет foreground-уведомление

#### Scenario: Разрешение уведомлений отклонено

- **WHEN** Android поддерживает runtime-разрешение уведомлений и пользователь его отклоняет
- **THEN** система объясняет, что уведомление может отсутствовать в notification drawer
- **AND** разрешает явно запустить foreground service, если Android допускает это
- **AND** сохраняет доступное действие остановки на главном экране

### Requirement: Production server не раскрывает debug diagnostics и будущие API

Production lifecycle MUST раздавать встроенные web assets без внешнего backend и MUST публиковать только завершённые маршруты текущего этапа: public web manifest и pairing entry points, защищённые session/status routes, авторизованный events WebSocket и защищённый `POST /api/v1/text`. Production server MUST NOT публиковать `/diagnostics/*`, диагностический bearer token, trusted-browser credentials, file-transfer routes или другие незавершённые `/api/v1/*`.

#### Scenario: Browser открывает production endpoint

- **WHEN** пользователь открывает показанный LAN URL работающего production server
- **THEN** корневая страница, связанные assets и `/web-manifest.json` доступны по текущему публичному контракту
- **AND** страница не выполняет внешних запросов

#### Scenario: Запрошен диагностический маршрут

- **WHEN** клиент release-сборки запрашивает `/diagnostics/health` или другой `/diagnostics/*`
- **THEN** сервер возвращает 404
- **AND** ответ не раскрывает диагностические сведения или token

#### Scenario: Запрошен реализованный защищённый маршрут

- **WHEN** клиент без действующего token запрашивает `/api/v1/status`, `POST /api/v1/text`, `DELETE /api/v1/session` или подключается к `/api/v1/events`
- **THEN** сервер применяет session authorization contract и не раскрывает приватные данные

#### Scenario: Авторизованный text route доступен

- **WHEN** действующая browser session отправляет допустимый запрос на `POST /api/v1/text`
- **THEN** server обрабатывает его по text protocol и возвращает определённый результат
- **AND** Android host получает не более одного входящего элемента для одного `messageId`

#### Scenario: Запрошен будущий production API

- **WHEN** любой client запрашивает `/api/v1/files` или `/api/v1/transfers/*` до реализации file-transfer change
- **THEN** server возвращает 404 независимо от наличия session token
- **AND** не выполняет пользовательскую операцию
