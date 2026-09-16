## ADDED Requirements

### Requirement: Wi-Fi lock ограничен активной файловой передачей

DeviceBridge MUST удерживать Wi-Fi lock только пока хотя бы один file stream фактически находится в состоянии transferring, и MUST освобождать его после последней active operation, cancellation, failure или server stop.

#### Scenario: Начался file stream

- **WHEN** первый transfer переходит в transferring
- **THEN** lifecycle получает один Wi-Fi lock для активных сетевых операций
- **AND** queued или verifying-only item сам по себе lock не удерживает

#### Scenario: Последний stream завершён

- **WHEN** больше нет file items в transferring
- **THEN** lifecycle освобождает Wi-Fi lock
- **AND** сервер продолжает обычную работу без длительного lock

#### Scenario: Server аварийно останавливается

- **WHEN** lifecycle завершается из-за сети, permission или ошибки
- **THEN** Wi-Fi lock освобождается в общем cleanup
- **AND** следующий запуск не наследует старый lock

## MODIFIED Requirements

### Requirement: Уведомление обеспечивает видимость и остановку сервера

При работающем сервере DeviceBridge MUST показывать постоянное foreground-уведомление с локальным endpoint, фактическим числом подключённых браузеров и безопасным состоянием активной text или file transfer. Уведомление MUST содержать действие «Остановить».

#### Scenario: Сервер успешно запущен

- **WHEN** server lifecycle переходит в состояние «Сервер запущен»
- **THEN** уведомление показывает доступный локальный адрес и актуальное число подключённых браузеров
- **AND** без активной операции не показывает фиктивную передачу

#### Scenario: Выполняется text transfer

- **WHEN** исходящий text item ожидает отправки или acknowledgement
- **THEN** уведомление показывает безопасный статус активной передачи без её содержимого
- **AND** после результата возвращается к обычному server status

#### Scenario: Выполняется file transfer

- **WHEN** file item находится в connecting, transferring или verifying
- **THEN** уведомление показывает имя безопасной длины, направление и общий progress без file content или path
- **AND** terminal result удаляет active transfer status

#### Scenario: Остановка из уведомления

- **WHEN** пользователь нажимает «Остановить» в уведомлении
- **THEN** система отменяет незавершённые text и file operations, закрывает streams и останавливает server
- **AND** удаляет foreground-уведомление и освобождает Wi-Fi lock

#### Scenario: Разрешение уведомлений отклонено

- **WHEN** Android поддерживает runtime-разрешение уведомлений и пользователь его отклоняет
- **THEN** система объясняет, что уведомление может отсутствовать в notification drawer
- **AND** разрешает явно запустить foreground service, если Android допускает это
- **AND** сохраняет доступное действие остановки на главном экране

### Requirement: Production server не раскрывает debug diagnostics и будущие API

Production lifecycle MUST раздавать встроенные web assets без внешнего backend и MUST публиковать только завершённые маршруты текущего этапа: public web manifest и pairing entry points, защищённые session/status/text/file control routes, авторизованный events WebSocket, scoped upload/download streams и cancellation. Production server MUST NOT публиковать diagnostics, trusted-browser credentials, history/settings APIs или другие незавершённые routes.

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

- **WHEN** действующая session создаёт допустимый file offer, upload, download grant или cancellation
- **THEN** server обрабатывает запрос по file protocol и ownership rules
- **AND** возвращает определённое state без публикации filesystem path

#### Scenario: Авторизованный text route доступен

- **WHEN** действующая browser session отправляет допустимый запрос на POST /api/v1/text
- **THEN** server обрабатывает его по text protocol и возвращает определённый результат
- **AND** Android host получает не более одного входящего элемента для одного messageId

#### Scenario: Запрошен будущий production API

- **WHEN** client запрашивает trusted-browser, persistent history или settings API до соответствующего change
- **THEN** server возвращает 404 независимо от session token
- **AND** не выполняет пользовательскую операцию
