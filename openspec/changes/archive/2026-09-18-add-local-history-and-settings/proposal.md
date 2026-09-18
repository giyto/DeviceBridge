## Why

После завершения двусторонней передачи текста, ссылок и файлов DeviceBridge уже выполняет основные операции, но пока не сохраняет полезную локальную историю, не предоставляет постоянные пользовательские настройки и требует нового pairing после каждого запуска сервера. Кроме того, стандартная иконка не идентифицирует продукт, а выбранный до отправки набор файлов нельзя удобно отредактировать на телефоне и компьютере.

## What Changes

- Добавляется локальная история текстовых и файловых операций на Android с фильтрами, деталями, удалением отдельных записей, полной очисткой и автоматическим сроком хранения.
- Добавляются локальные настройки имени телефона, папки сохранения, срока хранения истории и пользовательского лимита файла в пределах проверенного hard limit.
- Добавляется явная опция «Запомнить этот браузер», отдельный ограниченный по сроку trusted credential и экран отзыва доверенных браузеров на Android.
- Текущие transfer capabilities начинают записывать только безопасные метаданные и короткий текстовый preview; содержимое файлов и полный текст в историю не копируются.
- Android и web получают редактируемый черновик выбранных файлов до отправки: удаление отдельного элемента, очистку всего списка и добавление других файлов без удаления оригиналов.
- Отмена повторного системного picker сохраняет уже подготовленный черновик, а пустой черновик нельзя отправить.
- Android-приложение получает собственную adaptive launcher icon DeviceBridge с legacy fallback и monochrome-вариантом.
- История, настройки, trusted credentials и иконка остаются частью единственного Android APK; внешний backend, отдельное desktop-приложение и интернет не добавляются.

## Capabilities

### New Capabilities

- `local-history-and-settings`: локальная постоянная история операций, DataStore-настройки, выбранная через SAF папка, retention cleanup и управление доверенными браузерами.

### Modified Capabilities

- `android-app-shell`: реальные экраны истории и настроек, управление trusted browsers, редактируемый Android file draft и собственная launcher icon.
- `browser-web-interface`: опция доверия при pairing, восстановление через trusted credential и редактируемый browser file draft до подтверждения upload.
- `file-transfer`: разделение file draft и executable queue, удаление и добавление выбранных элементов до отправки, использование настроенного destination и запись безопасного результата в историю.
- `secure-browser-session`: отдельный persistent trusted credential с ограниченным сроком, обменом на session текущего server generation и немедленным отзывом с Android.
- `server-lifecycle`: production server публикует только необходимые защищённые trusted-session операции, сохраняя history/settings локальными для Android.
- `text-and-link-transfer`: terminal metadata и безопасный короткий preview записываются в локальную историю без сохранения полного текста.

## Impact

- Android: domain-модели и repository-интерфейсы, Room, DataStore, защищённое хранение trusted credentials, SAF persisted URI permission, Compose-экраны History/Settings/File preview и ресурсы launcher icon.
- Embedded server/protocol: защищённые команды запроса, применения и отзыва trusted credential; текущие session tokens остаются generation-scoped.
- Web: pairing UI, persistent хранение отдельного trusted credential, file draft state и доступные действия удаления/очистки.
- Transfer pipeline: terminal результаты передаются в history repository, а draft items не создают server transfer до подтверждения.
- Testing: Room/DataStore/repository tests, retention и revoke scenarios, Compose tests, web unit/Playwright coverage, API 29 и API 37.1 resource checks.
