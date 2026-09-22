# mvp-release-readiness Specification

## Purpose

Определяет проверяемый security и release-контракт MVP 1.0, при котором DeviceBridge поставляется как честно ограниченная LAN-only утилита и не публикуется при незакрытых критических рисках.

## Requirements

### Requirement: MVP использует явно ограниченную LAN-only модель

Публичный MVP 1.0 MUST использовать одно Android-приложение и локальный HTTP/WebSocket transport только в доверенной сети. Продукт MUST NOT заявлять шифрование локального трафика, работу через интернет, облачный relay или наличие desktop companion. Ограничение транспорта и рекомендация не передавать чувствительные данные в публичной либо чужой сети MUST быть доступны пользователю до начала передачи.

#### Scenario: Пользователь готовится передать данные

- **WHEN** пользователь запускает сервер либо открывает web-интерфейс перед первой передачей
- **THEN** интерфейс сообщает, что соединение работает только в локальной доверенной сети и HTTP не шифрует трафик
- **AND** предупреждение не маскируется формулировкой о полной безопасности или защищённом канале

#### Scenario: Интернет недоступен

- **WHEN** телефон и компьютер находятся в одной локальной сети без доступа в интернет
- **THEN** основные pairing, text и file flows остаются доступны
- **AND** приложение не требует облачной учётной записи, relay или внешнего backend

### Requirement: Release APK исключает debug-only поверхность

Release APK MUST содержать production web assets и только публичную поверхность MVP. Debug-only diagnostics, тестовые маршруты, fixture data, developer controls и подробные stack traces MUST быть недоступны в release runtime. Отсутствующий или запрещённый маршрут MUST возвращать безопасный ответ без раскрытия внутренней конфигурации, токенов и пользовательских данных.

#### Scenario: Запрошен диагностический маршрут

- **WHEN** клиент обращается к debug-only diagnostic endpoint в release-сборке
- **THEN** endpoint отсутствует или отвечает безопасным `404`
- **AND** ответ не раскрывает build internals, локальные пути, session data или stack trace

#### Scenario: Release APK открывает web UI

- **WHEN** пользователь устанавливает release APK и запускает локальный сервер
- **THEN** browser получает production bundle с версией assets, соответствующей содержимому APK
- **AND** страница не загружает runtime-ресурсы, аналитику или код с внешнего origin

### Requirement: Security gates подтверждают обязательные меры MVP

Перед публикацией release candidate MUST пройти автоматическую и ручную проверку поверхности маршрутов, pairing attempts и expiry, подтверждения на телефоне, session/trusted credentials, Origin/Host/CORS, MIME type, размера и имени файла, безопасной вставки текста, журналирования и остановки сервера при потере допустимой сети или разрешения. Любой failed security gate, допускающий неавторизованный доступ, утечку секрета либо ложный terminal success, MUST блокировать релиз.

#### Scenario: Неавторизованный клиент вызывает защищённый API

- **WHEN** запрос к защищённому HTTP или WebSocket API не содержит действующую credential либо приходит с запрещённым Host/Origin
- **THEN** операция отклоняется до обработки пользовательского payload
- **AND** ответ не раскрывает, существует ли конкретная session, trusted browser, текст или файл

#### Scenario: Security gate обнаруживает критическое нарушение

- **WHEN** release verification обнаруживает обход authorization, раскрытие token, внешний runtime endpoint, некорректный completed result или приём недопустимого файла
- **THEN** release candidate получает статус blocked
- **AND** APK не считается готовым до исправления причины и успешного повторного запуска соответствующего gate

### Requirement: Секреты и пользовательские данные не попадают в поставку и журналы

Repository, release APK, web bundle, generated manifests и verification reports MUST NOT содержать signing private key, password, pairing code, session/trusted token, содержимое переданного текста или файла, полный пользовательский путь либо иной рабочий секрет. Release logging MUST ограничиваться безопасными кодами состояния и техническими метаданными, необходимыми для диагностики без восстановления пользовательского payload.

#### Scenario: Выполняется проверка release artifact

- **WHEN** release candidate сканируется на известные секреты, внешние endpoints и запрещённые пользовательские данные
- **THEN** проверка не находит private signing material, credentials или transfer content
- **AND** найденное подозрительное значение блокирует публикацию до ручной классификации и устранения риска

#### Scenario: Операция завершается ошибкой

- **WHEN** release runtime записывает диагностическое событие об ошибке pairing, session, text или file operation
- **THEN** запись содержит безопасный код причины и допустимые технические признаки
- **AND** запись не содержит credential, pairing code, текст, имя с полным путём или содержимое файла

### Requirement: Release artifact имеет проверяемую идентичность

Каждый публикуемый APK MUST иметь однозначные version metadata, быть подписан release-ключом вне repository и сопровождаться криптографическим checksum. Release evidence MUST связывать checksum, commit, версию приложения, версию встроенных web assets и результаты обязательных gates. Обновление поверх предыдущей release-версии MUST сохранять совместимые локальные настройки и данные либо явно блокироваться до установки без потери данных.

#### Scenario: Формируется release candidate

- **WHEN** CI или ответственный разработчик создаёт APK для публикации
- **THEN** artifact получает непустые version name/code, действительную release-подпись и опубликованный SHA-256 checksum
- **AND** evidence указывает исходный commit и версию встроенного web bundle

#### Scenario: Пользователь обновляет приложение

- **WHEN** release candidate устанавливается поверх предыдущей подписанной MVP-сборки
- **THEN** Android принимает обновление при совместимой подписи и версии
- **AND** настройки, trusted-browser records, история и выбранная папка сохраняются без скрытого сброса

### Requirement: MVP release matrix закрывается до публикации

Release candidate MUST пройти заявленные unit, integration, UI и web gates, установку и smoke flows на Android API 29 и API 37.1, а также browser acceptance в актуальных Chrome и Edge на Windows 11. Матрица MUST включать server lifecycle, pairing/approval/revoke, trusted reconnect, text/link transfer, один и несколько файлов в обе стороны, cancel/retry, файл не менее 500 МБ с SHA-256 и отсутствие внешнего runtime-трафика. Непроверенная обязательная ячейка MUST оставаться видимым release blocker, а не считаться успешной по умолчанию.

#### Scenario: Все обязательные проверки успешны

- **WHEN** каждый автоматический gate и каждая обязательная ручная ячейка имеют подтверждённый результат `PASS`
- **THEN** release candidate может получить статус MVP 1.0 ready
- **AND** verification report фиксирует окружение, ожидаемый результат, фактический результат и известные остаточные риски

#### Scenario: Обязательная среда недоступна

- **WHEN** API 29, API 37.1, Chrome/Edge либо Windows 11 не были фактически проверены для текущего release candidate
- **THEN** соответствующая ячейка остаётся `NOT RUN` или `BLOCKED`, но не `PASS`
- **AND** публичная готовность MVP не подтверждается до выполнения либо явного изменения ТЗ
