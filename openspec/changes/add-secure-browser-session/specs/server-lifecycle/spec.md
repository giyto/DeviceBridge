## ADDED Requirements

### Requirement: Browser sessions завершаются вместе с server lifecycle

Production server MUST владеть pairing codes, pending requests, browser sessions, session WebSockets и tokens в границах текущего server generation. Любое завершение lifecycle MUST аннулировать их до освобождения listener и MUST NOT восстанавливать их после следующего запуска.

#### Scenario: Пользователь останавливает сервер

- **WHEN** пользователь останавливает production server из Android UI или уведомления
- **THEN** server закрывает session WebSockets и аннулирует все codes, pending requests и tokens текущего generation
- **AND** затем освобождает listening endpoint и foreground notification

#### Scenario: Сеть или разрешение потеряны

- **WHEN** lifecycle переходит в recoverable error из-за потери сети, смены IPv4 или отзыва локального разрешения
- **THEN** все browser sessions завершаются до публикации error state
- **AND** новый endpoint после явного запуска требует новый pairing

## MODIFIED Requirements

### Requirement: Production server не раскрывает debug diagnostics и будущие API

Production lifecycle MUST раздавать встроенные web assets без внешнего backend и MUST публиковать только завершённые маршруты текущего этапа: public web manifest и pairing entry points, защищённые session/status routes и авторизованный events WebSocket. Production server MUST NOT публиковать `/diagnostics/*`, диагностический bearer token, trusted-browser credentials, transfer routes или другие незавершённые `/api/v1/*`.

#### Scenario: Browser открывает production endpoint

- **WHEN** пользователь открывает показанный LAN URL работающего production server
- **THEN** корневая страница, связанные assets и `/web-manifest.json` доступны по текущему публичному контракту
- **AND** страница не выполняет внешних запросов

#### Scenario: Запрошен диагностический маршрут

- **WHEN** клиент release-сборки запрашивает `/diagnostics/health` или другой `/diagnostics/*`
- **THEN** сервер возвращает 404
- **AND** ответ не раскрывает диагностические сведения или token

#### Scenario: Запрошен реализованный защищённый маршрут

- **WHEN** клиент без действующего token запрашивает `/api/v1/status`, `DELETE /api/v1/session` или подключается к `/api/v1/events`
- **THEN** сервер применяет session authorization contract и не раскрывает приватные данные

#### Scenario: Запрошен будущий production API

- **WHEN** любой клиент запрашивает `/api/v1/text`, `/api/v1/files` или `/api/v1/transfers/*` до реализации соответствующего change
- **THEN** сервер возвращает 404 независимо от наличия session token
- **AND** не выполняет пользовательскую операцию
