## ADDED Requirements

### Requirement: Web shell формирует точную информационную browser label

Web shell MUST определять понятную browser family по доступным runtime hints с приоритетом product-specific Chromium-признаков перед общим Chrome/Chromium fallback. Проверка `YaBrowser` MUST выполняться до `Chrome`; неизвестный или неоднозначный client MUST называться нейтрально.

#### Scenario: Страница открыта в Яндекс Браузере

- **WHEN** runtime browser hints содержат признак `YaBrowser` вместе с совместимым признаком `Chrome`
- **THEN** pairing request использует метку «Яндекс Браузер»
- **AND** не использует метку Chrome или Google Chrome

#### Scenario: Страница открыта в Edge

- **WHEN** runtime browser hints содержат признак Edge и общий Chromium-признак
- **THEN** pairing request использует метку Edge
- **AND** общий Chrome fallback не переопределяет более точный результат

#### Scenario: Browser не распознан однозначно

- **WHEN** доступные hints не соответствуют поддерживаемой browser family
- **THEN** web shell использует нейтральную метку «Браузер» с безопасной платформой при наличии
- **AND** не отправляет полный сырой User-Agent как отображаемое название

### Requirement: Web shell показывает text flow текущей session

После pairing web shell MUST показывать доступную text-форму и ленту элементов текущей session, сохраняя основные действия при ширине viewport от 360 до 1920 пикселей и в светлой или тёмной теме.

#### Scenario: Session подключена

- **WHEN** pairing завершён и token действителен
- **THEN** text-форма становится доступной без перезагрузки страницы
- **AND** file actions остаются недоступными с понятным объяснением

#### Scenario: Session потеряна во время ввода

- **WHEN** token отозван или WebSocket сообщает потерю авторизации
- **THEN** отправка блокируется и web shell возвращается к pairing state
- **AND** не отправляет введённое содержимое без новой явной команды после повторного подключения

#### Scenario: Управление с клавиатуры

- **WHEN** пользователь проходит text-форму и ленту клавишей Tab
- **THEN** поле, отправка, копирование и открытие ссылки имеют видимый фокус и понятные доступные названия

## MODIFIED Requirements

### Requirement: Недоступные функции не имитируют готовую работу

После session authorization web shell MUST позволять отправлять и получать текст через реализованную text capability, но MUST NOT позволять загружать или скачивать файлы до отдельного file-transfer change. Интерфейс MUST различать доступность local server, авторизацию session, готовность text transfer и недоступность file transfer.

#### Scenario: Пользователь открывает web shell третьего этапа

- **WHEN** локальный server доступен, но browser client ещё не авторизован
- **THEN** пользователь видит pairing form, а text и file actions недоступны
- **AND** HTTP availability не обозначается как авторизованная WebSocket session

#### Scenario: Browser успешно подключён

- **WHEN** pairing завершён и session token действителен
- **THEN** web shell показывает подключённое состояние, защищённый server status и доступную text-форму
- **AND** file actions остаются недоступными с объяснением следующего этапа

#### Scenario: Text capability временно недоступна

- **WHEN** авторизованная session потеряла events connection или получила protocol error
- **THEN** web shell не имитирует успешную отправку
- **AND** показывает понятную ошибку и действие восстановления соединения
