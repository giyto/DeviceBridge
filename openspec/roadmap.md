# DeviceBridge OpenSpec Roadmap

## Продуктовая граница

DeviceBridge создаётся как одно Android-приложение. Телефон поднимает локальный HTTP/WebSocket-сервер, раздаёт встроенный веб-интерфейс и хранит данные. На компьютере пользователь открывает браузер; отдельное Windows-приложение, внешний backend, аренда сервера и обязательная покупка инфраструктуры не входят в текущую версию.

Основной источник требований — docs/technical-specification.md версии 2.0. Каждый change должен сохранять Clean Architecture, MVVM и SOLID, а инфраструктурные детали Ktor, Room и Android API не должны проникать во ViewModel и domain.

## Порядок изменений

### 1. establish-android-foundation — завершён и архивирован

Цель: создать проверяемую основу одного Android-приложения.

Результат: app shell, package structure для Clean Architecture, базовый UI/state, minSdk 29, targetSdk 37 и тесты API 29/API 37.1.

Архив: openspec/changes/archive/2026-09-14-establish-android-foundation.

### 2. validate-embedded-ktor-server — завершён и архивирован

Цель: снять главный технический риск одноприложенной архитектуры до разработки функций.

Результат: debug-only spike Ktor 3.5.2 + CIO, HTTP health, WebSocket, streaming 500 МБ, lifecycle, cancellation, измерения памяти/APK и verdict accept либо replace на API 29 и API 37.1.

Зависимость: этап 1.

Следующий этап разрешён только при verdict=accept. При verdict=replace сначала создаётся отдельный change замены server adapter.

Архив: openspec/changes/archive/2026-09-14-validate-embedded-ktor-server.

### 3. bundle-browser-web-interface — завершён и архивирован

Цель: встроить в APK минимальную статическую веб-страницу и воспроизводимый Vite build pipeline.

Результат: Android-сервер раздаёт versioned web assets; браузер с компьютера открывает страницу и отображает статус соединения. Пользовательские операции ещё не добавляются.

Зависимость: положительный результат этапа 2.

Архив: openspec/changes/archive/2026-09-14-bundle-browser-web-interface.

### 4. add-server-lifecycle — завершён и архивирован

Цель: превратить принятый spike в production lifecycle локального сервера.

Результат: foreground service, запуск/остановка из приложения, уведомление, LAN address/port, permission flow API 37, обработка смены сети и восстановление состояния. На этом этапе вводится Hilt для production-компонентов.

Зависимость: этапы 2–3.

Архив: openspec/changes/archive/2026-09-15-add-server-lifecycle.

### 5. add-secure-browser-session — завершён и архивирован

Цель: разрешать доступ только подтверждённому браузеру в локальной сети.

Результат: pairing/session token, срок действия, отзыв сессии, защита HTTP и WebSocket, безопасное хранение секретов и ограничения origin/host. Диагностический токен spike не переиспользуется как production-решение.

Зависимость: этап 4.

Архив: openspec/changes/archive/2026-09-16-add-secure-browser-session.

### 6. add-text-and-link-transfer — завершён и архивирован

Цель: реализовать ежедневный обмен текстом и ссылками между телефоном и браузером.

Результат: двусторонняя отправка, получение, копирование, валидация protocol DTO и понятные состояния ошибок.

Зависимость: этап 5.

Архив: openspec/changes/archive/2026-09-16-add-text-and-link-transfer.

### 7. add-file-transfer — завершён и архивирован

Цель: реализовать двустороннюю передачу файлов без внешнего сервера.

Результат: streaming upload/download, Android Storage Access Framework, прогресс, отмена, проверка размера/целостности и безопасные имена файлов. Реализация не буферизует файл целиком.

Зависимость: этап 6 и подтверждённые streaming-ограничения этапа 2.

Архив: openspec/changes/archive/2026-09-17-add-file-transfer.

### 8. add-local-history-and-settings — завершён и архивирован

Цель: сделать приложение удобным для постоянного личного использования.

Результат: локальная история операций в Room, очистка и ограничения хранения, настройки приложения и список доверенных браузеров. Дополнительно Android и web получают редактируемый черновик выбранных файлов до отправки, а Android-приложение — собственную adaptive launcher icon. Domain работает через repository-интерфейсы.

Зависимость: этапы 5–7.

Архив: openspec/changes/archive/2026-09-18-add-local-history-and-settings.

### 9. harden-errors-and-accessibility — завершён и архивирован

Цель: довести основные сценарии до устойчивого пользовательского качества.

Результат: единая модель ошибок, retry и отмена, состояния загрузки, пустоты и ошибки, доступность, понятные инструкции подключения.

Архив: openspec/changes/archive/2026-09-19-harden-errors-and-accessibility.

### 10. redesign-midnight-bridge-interface — завершён и архивирован

Цель: сделать Android и web цельным узнаваемым продуктом.

Результат: визуальная система Midnight Bridge, переработанные Home, Text и Files, компактное предупреждение HTTP на странице.

Архив: openspec/changes/archive/2026-09-19-redesign-midnight-bridge-interface.

### 11. add-server-quick-tile-and-idle-stop — завершён и архивирован

Цель: запускать и останавливать сервер без открытия приложения и не забывать его включённым.

Результат: плитка в быстрых настройках и автоостановка сервера без подключений.

Архив: openspec/changes/archive/2026-09-23-add-server-quick-tile-and-idle-stop.

### 12. add-trusted-auto-accept-and-clipboard-paste — завершён и архивирован

Цель: убрать лишние действия в самых частых сценариях.

Результат: автоприём файлов от запомненных браузеров и вставка изображения из буфера обмена в черновик на странице.

Архив: openspec/changes/archive/2026-09-23-add-trusted-auto-accept-and-clipboard-paste.

### 13. prepare-mvp-security-release — завершён и архивирован

Цель: подтвердить безопасность и готовность локальной версии 1.0.

Результат: security review локального HTTP/WebSocket, выбор транспортной модели (ADR-0001), подпись release-сборки и релизный чек-лист.

Архив: openspec/changes/archive/2026-09-23-prepare-mvp-security-release.

### 14. add-event-notifications — завершён и архивирован

Цель: сообщать о событиях, когда приложение или страница не на экране.

Результат: уведомления о запросе подключения, входящих файлах и тексте на телефоне и системные уведомления на странице в защищённом режиме.

Архив: openspec/changes/archive/2026-09-24-add-event-notifications.

### 15. add-https-secure-mode — завершён и архивирован

Цель: шифровать трафик в локальной сети.

Результат: необязательный защищённый режим: собственный корневой сертификат телефона с ограничением на локальные адреса, HTTPS и страница установки сертификата.

Архив: openspec/changes/archive/2026-09-24-add-https-secure-mode.

### 16. add-resumable-file-transfer — завершён и архивирован

Цель: не терять уже переданное при обрыве.

Результат: продолжение загрузки с сохранённого места для файлов от 8 МиБ и HTTP Range для скачивания с телефона.

Архив: openspec/changes/archive/2026-09-24-add-resumable-file-transfer.

### 17. add-local-name-and-quick-entry — завершён и архивирован

Цель: постоянный адрес и быстрый повторный вход.

Результат: адрес по имени devicebridge.local через mDNS, настройка «Имя в сети», вход только по одному адресу, автоматическое переподключение вкладки, решение запроса подключения из уведомления.

Архив: openspec/changes/archive/2026-09-25-add-local-name-and-quick-entry.

### 18. add-folder-upload — предложение, не начат

Цель: отправлять папку целиком с компьютера на телефон.

Результат: предложение составлено и ожидает решения о реализации: одно подтверждение на папку, подпапки на телефоне, общий прогресс, отмена и повтор.

## Правило завершения change

Для каждого этапа применяется один цикл:

1. Согласовать proposal, delta-spec, design и tasks с ТЗ.
2. Реализовать tasks с тестами.
3. Выполнить автоматические и требуемые ручные проверки.
4. Провести review и синхронизировать delta-spec с openspec/specs.
5. Архивировать завершённый change.
6. Закоммитить и отправить в GitHub код, документацию, main specs и архив.

Новый change не должен незаметно расширять продукт до второго desktop-приложения или облачной инфраструктуры. Такое изменение потребует отдельного пересмотра ТЗ.
