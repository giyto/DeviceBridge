# file-transfer Specification

## Purpose

Определить безопасную и потоковую передачу одного или нескольких файлов между Android host и выбранной browser session без внешнего backend и без буферизации полного файла в памяти.

## Requirements

### Requirement: Файлы передаются только через активную browser session

DeviceBridge MUST создавать файловую операцию только для действующей авторизованной browser session. Android → Browser transfer MUST иметь одного явно выбранного получателя, а Browser → Android transfer MUST принадлежать session, которая его создала.

#### Scenario: Android отправляет выбранному браузеру

- **WHEN** пользователь выбирает файлы, активную browser session и подтверждает отправку
- **THEN** предложение получает только выбранная session
- **AND** остальные подключённые браузеры не получают metadata или содержимое

#### Scenario: Browser предлагает файлы телефону

- **WHEN** авторизованный browser client подтверждает upload выбранных файлов
- **THEN** Android host получает предложение с указанием этой session
- **AND** поток содержимого не принимается до пользовательского решения на Android

#### Scenario: Session отозвана

- **WHEN** session теряет авторизацию до завершения принадлежащей ей операции
- **THEN** её queued и active transfers завершаются отменой или ошибкой
- **AND** другой browser client не может продолжить или получить их содержимое

### Requirement: File metadata и лимиты валидируются до передачи

Каждый file item MUST иметь protocolVersion, transferId, messageId, безопасное display name, заявленный размер, MIME type, направление и timestamp. DeviceBridge MUST принимать файл размером от 0 байт до 1 ГиБ включительно, где 1 ГиБ равен 1 073 741 824 байтам, и MUST отклонять недопустимые metadata до открытия пользовательского output. До отдельного settings change runtime limit фиксирован; будущая пользовательская настройка MAY уменьшать его, но MUST NOT увеличивать выше проверенного hard limit без отдельного изменения спецификации.

#### Scenario: Выбран допустимый набор файлов

- **WHEN** пользователь выбирает один или несколько доступных файлов в пределах опубликованного лимита
- **THEN** система создаёт отдельный наблюдаемый item для каждого файла
- **AND** показывает имя, MIME type и размер до подтверждения

#### Scenario: Файл превышает лимит

- **WHEN** заявленный или фактически полученный размер превышает 1 073 741 824 байта
- **THEN** операция завершается ошибкой с понятным сообщением
- **AND** полный payload не сохраняется и не остаётся в очереди как успешный

#### Scenario: Передаётся пустой файл

- **WHEN** пользователь подтверждает файл размером 0 байт
- **THEN** система передаёт metadata и проверяет SHA-256 пустого содержимого
- **AND** item может завершиться completed по тем же ownership и verification rules

#### Scenario: Фактический размер отличается

- **WHEN** число принятых байтов не совпадает с подтверждённым metadata
- **THEN** transfer не переходит в completed
- **AND** partial output очищается либо однозначно помечается незавершённым

### Requirement: Передача и checksum выполняются потоково

DeviceBridge MUST читать, передавать, записывать и хешировать файл ограниченными chunks без преобразования полного файла в ByteArray, String, ArrayBuffer или Blob в памяти. Обязательный контрольный файл размером 500 МБ MUST передаваться в обоих направлениях без роста памяти пропорционально размеру файла; тот же streaming contract MUST применяться ко всему диапазону до 1 ГиБ включительно.

#### Scenario: Browser загружает файл размером 500 МБ

- **WHEN** авторизованный browser client передаёт допустимый файл размером 500 МБ
- **THEN** Android host записывает и хеширует входящий поток инкрементально
- **AND** UI остаётся отзывчивым и не удерживает полный payload в памяти

#### Scenario: Browser скачивает файл размером 500 МБ

- **WHEN** пользователь принимает Android → Browser предложение и начинает download
- **THEN** server читает Android content URI и отдаёт response потоково
- **AND** web runtime не собирает полный файл в памяти для запуска загрузки

#### Scenario: Выполняется checksum

- **WHEN** отправитель либо получатель вычисляет SHA-256
- **THEN** вычисление выполняется на последовательности chunks вне UI thread
- **AND** прогресс или состояние verifying остаётся наблюдаемым

### Requirement: Очередь ограничивает параллельные file transfers

В пределах одного server generation DeviceBridge MUST выполнять не более одной активной файловой передачи Android → Browser и одной Browser → Android. Остальные подтверждённые items MUST иметь состояние queued и запускаться в порядке очереди соответствующего направления.

#### Scenario: Добавлено несколько файлов

- **WHEN** пользователь подтверждает набор из нескольких файлов
- **THEN** каждый файл появляется отдельным item в стабильном порядке
- **AND** только первый доступный item направления переходит к connecting или transferring

#### Scenario: Направления работают одновременно

- **WHEN** в каждом направлении имеется готовый item
- **THEN** не более одной операции каждого направления может выполняться одновременно
- **AND** прогресс и cancellation одной операции не подменяют состояние другой

#### Scenario: Передаётся текст

- **WHEN** активна файловая передача и session сохраняет events connection
- **THEN** пользователь может отправлять и получать text items
- **AND** file queue не блокирует text protocol

### Requirement: Browser upload сохраняется только в выбранное Android location

До приёма Browser → Android payload пользователь MUST подтвердить предложение и выбрать доступную папку назначения через системный Android picker. Выданный доступ MUST быть ограничен выбранным location и текущими операциями этого change; широкое разрешение на хранилище MUST NOT запрашиваться.

#### Scenario: Android подтверждает входящие файлы

- **WHEN** browser предлагает один или несколько файлов и пользователь выбирает папку
- **THEN** queued uploads получают разрешённое destination
- **AND** server начинает принимать payload только после подтверждения

#### Scenario: Пользователь отменяет выбор папки

- **WHEN** системный picker закрыт без результата
- **THEN** предложение не начинает передачу
- **AND** browser получает cancelled или понятное состояние ожидания нового решения

#### Scenario: Папка стала недоступна

- **WHEN** provider отзывает доступ либо output stream не может быть открыт
- **THEN** transfer завершается контролируемой ошибкой
- **AND** приложение предлагает повторно выбрать location без crash

### Requirement: Browser download завершается без повторного выбора файла

Android → Browser item MUST оставаться предложением до явного действия пользователя в web UI. На LAN HTTP web shell MUST запускать нативную загрузку без помещения полного response в память. После успешной отдачи заявленного количества байтов server MUST установить completed и MUST NOT требовать повторного выбора скачанного файла.

#### Scenario: Browser принимает предложение

- **WHEN** пользователь нажимает действие скачивания для доступного item
- **THEN** браузер начинает нативную загрузку с безопасным предложенным именем
- **AND** server сообщает progress фактически переданных байтов

#### Scenario: Download stream завершён

- **WHEN** server передал заявленное число байтов
- **THEN** item переходит в completed
- **AND** следующий queued item того же направления больше не блокируется

#### Scenario: Download stream прерван

- **WHEN** response оборван до передачи заявленного числа байтов
- **THEN** item переходит в failed
- **AND** пользователь может явно повторить загрузку без повторного выбора исходного файла

### Requirement: Прогресс и terminal state наблюдаемы

Для каждого item интерфейсы MUST показывать направление, имя, размер, переданные байты, проценты, скорость и одно из состояний queued, connecting, transferring, verifying, completed, cancelled или failed. Progress MUST быть монотонным в пределах этапа передачи и MUST NOT раскрывать содержимое файла в notification или чужой session.

#### Scenario: Идёт передача

- **WHEN** поток передаёт байты
- **THEN** Android и принадлежащая operation browser session получают ограниченные progress updates
- **AND** displayed bytes не уменьшаются до смены terminal state

#### Scenario: Операция завершилась

- **WHEN** transfer получает completed, cancelled или failed
- **THEN** terminal state больше не возвращается к active state
- **AND** итог содержит понятную причину без внутренних stack traces

#### Scenario: Browser обновляет страницу

- **WHEN** вкладка обновлена и её tab-scoped session token остаётся действительным
- **THEN** web UI получает bounded snapshot текущих transfers этой session
- **AND** snapshot не запускает оборванный payload повторно без явной команды

### Requirement: Cancellation освобождает ресурсы и partial output

Пользователь MUST иметь возможность отменить queued или active transfer с Android и из принадлежащей browser session. Cancellation, network failure, session revoke и server stop MUST закрывать streams, file descriptors и coroutine jobs, удалять partial output когда provider это допускает и освобождать очередь.

#### Scenario: Отменён queued item

- **WHEN** пользователь отменяет item до начала потока
- **THEN** item удаляется из executable queue и становится cancelled
- **AND** сетевое тело или output не открывается

#### Scenario: Пользователь повторяет отменённый или failed item

- **WHEN** владеющая session явно запрашивает retry в том же server generation
- **THEN** тот же item с сохранёнными metadata возвращается в FIFO с нулевым progress
- **AND** доступный Android source сохраняется до completed, revoke или server stop, а дубликат item не создаётся

#### Scenario: Отменён active upload

- **WHEN** пользователь отменяет Browser → Android transfer
- **THEN** входящий stream и output закрываются
- **AND** partial document удаляется либо ясно помечается незавершённым

#### Scenario: Server остановлен

- **WHEN** lifecycle покидает Running
- **THEN** все незавершённые transfers получают конечное состояние отмены или ошибки
- **AND** новый server generation не восстанавливает payload или очередь прошлого generation

### Requirement: Имена файлов не приводят к path traversal или скрытой перезаписи

Remote filename и MIME type MUST считаться недоверенными metadata. DeviceBridge MUST удалить path components и управляющие символы, ограничить длину display name, создать нейтральное имя при необходимости и MUST NOT незаметно перезаписывать существующий файл.

#### Scenario: Имя содержит путь

- **WHEN** remote metadata содержит separators, dot segments или управляющие символы
- **THEN** destination использует нормализованное leaf name
- **AND** операция не может выйти из выбранного пользователем location

#### Scenario: Имя уже существует

- **WHEN** destination уже содержит файл с таким именем
- **THEN** система выбирает видимое уникальное имя либо требует явного решения пользователя
- **AND** существующий файл не изменяется скрыто

#### Scenario: MIME type недостоверен

- **WHEN** заявленный MIME type отсутствует или не соответствует provider metadata
- **THEN** система использует безопасный generic type или проверенное локальное значение
- **AND** MIME type не влияет на авторизацию transfer

### Requirement: File protocol идемпотентен и ограничен

Повтор control-команды с тем же messageId и тем же payload в одной session и server generation MUST возвращать согласованный результат без создания второго item. Повтор с конфликтующим payload, неизвестной major protocol version или неизвестным transferId MUST отклоняться без открытия stream.

#### Scenario: Повторено file offer

- **WHEN** session повторяет ранее принятую control-команду с тем же messageId и payload
- **THEN** server возвращает ранее определённый transferId и state
- **AND** очередь не получает дубликат

#### Scenario: Повтор имеет другое содержимое

- **WHEN** messageId повторно используется с отличающимися metadata
- **THEN** server возвращает conflict
- **AND** исходный item не изменяется

#### Scenario: Неизвестная версия

- **WHEN** file command или event использует неподдерживаемую major protocol version
- **THEN** получатель возвращает version error
- **AND** payload не передаётся и output не создаётся

### Requirement: File transfer не становится постоянной историей

До отдельного history change DeviceBridge MUST хранить file metadata, queue и результаты только в памяти текущего server generation. Содержимое файлов MUST находиться только в source URI, выбранном destination или явно обозначенном partial output и MUST NOT попадать в Android backup.

#### Scenario: Transfer завершён

- **WHEN** item получает terminal state
- **THEN** его metadata может оставаться в bounded текущей session до остановки сервера
- **AND** запись Room или постоянная история не создаётся

#### Scenario: Запущен новый server generation

- **WHEN** пользователь снова запускает сервер после остановки
- **THEN** прежняя file queue и session transfer cards отсутствуют
- **AND** уже сохранённые пользовательские файлы не удаляются
