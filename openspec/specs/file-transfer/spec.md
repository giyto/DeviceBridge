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

Каждый file item MUST иметь protocolVersion, transferId, messageId, безопасное display name, заявленный размер, MIME type, направление и timestamp. DeviceBridge MUST иметь hard limit 1 073 741 824 байта и effective limit из локальных настроек в диапазоне от 1 байта до hard limit включительно. Файл размером от 0 байт до effective limit MUST приниматься, а превышающий effective или hard limit MUST отклоняться до открытия пользовательского output.

#### Scenario: Выбран допустимый набор файлов
- **WHEN** пользователь выбирает один или несколько доступных файлов не больше текущего effective limit
- **THEN** система создаёт отдельный наблюдаемый item для каждого подтверждённого файла
- **AND** показывает имя, MIME type и размер до подтверждения

#### Scenario: Файл превышает лимит
- **WHEN** заявленный или фактически полученный размер превышает current effective limit либо hard limit 1 073 741 824 байта
- **THEN** операция завершается ошибкой с понятным сообщением и фактическим допустимым лимитом
- **AND** полный payload не сохраняется и не остаётся в очереди как успешный

#### Scenario: Передаётся пустой файл
- **WHEN** пользователь подтверждает файл размером 0 байт
- **THEN** система передаёт metadata и проверяет SHA-256 пустого содержимого
- **AND** item может завершиться completed по тем же ownership и verification rules

#### Scenario: Фактический размер отличается
- **WHEN** число принятых байтов не совпадает с подтверждённым metadata
- **THEN** transfer не переходит в completed
- **AND** partial output очищается либо однозначно помечается незавершённым

#### Scenario: Пользователь уменьшил лимит
- **WHEN** settings применяют новый effective limit ниже hard limit
- **THEN** Android и web используют новое значение для следующих draft items и server validation
- **AND** уже active transfer не меняет terminal result задним числом

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

До приёма Browser → Android payload пользователь MUST подтвердить предложение. Destination MUST быть либо ранее выбранной доступной default folder с действующим persistable SAF permission, либо папкой, явно выбранной через системный picker для текущего решения. Выданный доступ MUST быть ограничен выбранным location; широкое storage permission MUST NOT запрашиваться.

#### Scenario: Android подтверждает входящие файлы
- **WHEN** browser предлагает один или несколько допустимых файлов
- **THEN** Android показывает sender, metadata и доступную destination либо действие выбора папки
- **AND** server начинает принимать payload только после явного подтверждения

#### Scenario: Доступна папка по умолчанию
- **WHEN** browser предлагает допустимые files и сохранённая default destination доступна
- **THEN** Android показывает эту destination вместе с offer и действиями подтверждения или изменения папки
- **AND** payload не принимается до явного подтверждения пользователя

#### Scenario: Папка по умолчанию отсутствует
- **WHEN** пользователь принимает offer без доступной default destination
- **THEN** Android открывает системный folder picker до начала payload
- **AND** queued uploads получают destination только после успешного результата picker

#### Scenario: Пользователь отменяет выбор папки
- **WHEN** системный picker закрыт без результата
- **THEN** предложение не начинает передачу и ранее сохранённая допустимая destination не очищается
- **AND** browser получает понятное состояние ожидания нового решения или отмены

#### Scenario: Пользователь меняет destination для offer
- **WHEN** пользователь выбирает другую доступную папку для текущего предложения
- **THEN** Android использует подтверждённую папку для этого batch
- **AND** обновляет default destination только после отдельного явного согласия пользователя

#### Scenario: Папка стала недоступна
- **WHEN** provider отзывает доступ либо output stream не может быть открыт
- **THEN** transfer не начинает payload или завершается контролируемой ошибкой
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

DeviceBridge MUST сохранять в локальной Android history только безопасные metadata terminal file operation: transfer identifier, display name, MIME type, размер, направление, время, browser label, checksum при наличии и terminal status. Содержимое, executable queue, download grants и source URI MUST оставаться только в границах текущего server generation или выбранного пользовательского storage и MUST NOT копироваться в history или Android backup.

#### Scenario: Transfer завершён
- **WHEN** item получает completed, cancelled или failed
- **THEN** history получает не более одной record с безопасными metadata и итоговым status
- **AND** запись не содержит file bytes, bearer token, grant или filesystem path

#### Scenario: History record удалена
- **WHEN** пользователь удаляет file history record
- **THEN** metadata исчезают из истории
- **AND** source и уже сохранённый destination file не удаляются

#### Scenario: Запущен новый server generation
- **WHEN** пользователь снова запускает сервер после остановки
- **THEN** прежняя executable queue, source URI references и session transfer cards отсутствуют
- **AND** terminal history records сохраняются до явного удаления или retention cleanup

#### Scenario: History storage недоступен
- **WHEN** запись terminal metadata временно не удалась
- **THEN** фактический transfer result и целостность пользовательского файла не изменяются
- **AND** Android показывает отдельную безопасную ошибку истории без повторной передачи payload

### Requirement: File selection остаётся draft до подтверждения

На Android и в web выбранные исходные files MUST оставаться локальным editable draft до явного подтверждения. Draft item MUST NOT иметь transferId или попадать в executable queue; удаление item MUST затрагивать только draft reference, а не исходный файл.

#### Scenario: Item удалён до отправки
- **WHEN** пользователь удаляет выбранный item из Android или browser draft
- **THEN** система освобождает только draft reference и обновляет общий размер selection
- **AND** не открывает stream, не отправляет cancellation и не удаляет source

#### Scenario: К draft добавлены файлы
- **WHEN** пользователь выбирает дополнительные files до подтверждения
- **THEN** допустимые уникальные items добавляются после существующих
- **AND** validation применяется к каждому новому item до создания transfer

#### Scenario: Draft подтверждён
- **WHEN** пользователь подтверждает непустой draft с допустимым recipient и metadata
- **THEN** каждый принятый item получает ровно один transferId и попадает в FIFO соответствующего направления
- **AND** отклонённый item остаётся в draft с ошибкой, а повторный UI event не создаёт дубликат принятой операции

### Requirement: File failure сохраняет корректную очередь и безопасный retry

File transfer UI MUST различать queued, connecting, transferring, verifying, completed, cancelled, failed и retrying без возврата terminal item в active state самопроизвольно. Network loss, destination unavailable, insufficient space, checksum mismatch, file limit, source unavailable, session revoke и protocol mismatch MUST иметь отдельные причины. Manual retry MUST сохранять исходный transfer identifier и доступный source только для повторения той же неизменённой операции.

#### Scenario: Active transfer завершился сетевой ошибкой

- **WHEN** поток обрывается до terminal result и source всё ещё доступен в том же server generation
- **THEN** item становится failed, очередь освобождается для следующего item и UI показывает manual retry
- **AND** transfer не перезапускается автоматически после reconnect

#### Scenario: Verification не прошла

- **WHEN** итоговый checksum не совпадает с заявленным
- **THEN** item становится failed с причиной checksum mismatch
- **AND** partial или повреждённый output удаляется либо явно остаётся недоступным как completed file

#### Scenario: Source изменился или недоступен

- **WHEN** перед retry исходный source больше недоступен либо metadata не совпадают с исходной операцией
- **THEN** retry отклоняется как terminal для этого item
- **AND** UI предлагает выбрать файл заново и создать новую operation

#### Scenario: Retry той же операции подтверждён

- **WHEN** пользователь повторяет failed transfer с тем же доступным source и metadata
- **THEN** item возвращается в FIFO с нулевым progress и исходным transfer identifier
- **AND** дубликат queue item и скрытая перезапись completed output не создаются

### Requirement: Cancellation и destination errors не блокируют следующие файлы

Cancellation или terminal failure одного item MUST закрыть его streams и descriptors, освободить соответствующий queue slot и позволить продолжить независимые queued items. Отмена системного picker MUST возвращать пользователя к сохранённому file draft или offer, если source ещё доступен, а не удалять выбор без подтверждения.

#### Scenario: Первый файл отменён при наличии очереди

- **WHEN** пользователь отменяет active item и за ним есть независимый queued item
- **THEN** первый item становится cancelled и освобождает ресурсы
- **AND** следующий item может начать передачу по правилам параллельности очереди

#### Scenario: Пользователь отменил folder picker

- **WHEN** incoming offer ожидал destination и пользователь закрыл системный picker без выбора
- **THEN** offer или draft остаётся в состоянии awaiting destination
- **AND** пользователь может повторно открыть picker либо явно отменить offer

#### Scenario: В destination недостаточно места

- **WHEN** Android обнаруживает недостаточное свободное место до или во время записи
- **THEN** текущий item получает failed state с причиной insufficient space и partial output очищается, если provider это допускает
- **AND** другие queue items не объявляются completed и могут быть отменены или продолжены независимо

### Requirement: File progress остаётся доступным и не создаёт шум

Для каждого file item UI SHALL показывать текстовое направление, имя, размер, текущий этап и terminal result; цвет и процент MUST NOT быть единственным способом передачи статуса. Screen reader MUST получать объявления смены этапа и terminal result, но не каждого изменения байтов или процентов.

#### Scenario: Screen reader наблюдает передачу

- **WHEN** file item проходит transferring, verifying и completed
- **THEN** вспомогательная технология получает краткие объявления значимых этапов и результата
- **AND** focus пользователя не перемещается на progress indicator автоматически

#### Scenario: Прогресс недоступен временно

- **WHEN** точное число переданных байтов ещё неизвестно
- **THEN** UI показывает неопределённый progress и текущий этап текстом
- **AND** не отображает ложные 100 процентов до completed

### Requirement: File cards имеют устойчивую визуальную иерархию

Android и web MUST показывать каждый file item в одной operational card с type icon, безопасным display name, размером, направлением, stage, текстовым status и применимыми actions. Progress bar, скорость и remaining time MUST показываться только при достоверных данных и MUST NOT заменять текстовый stage. Controls MUST оставаться внутри карточки при длинном имени, font scale 200 процентов, browser zoom 200 процентов и narrow viewport.

#### Scenario: Длинное имя файла не помещается

- **WHEN** display name занимает больше доступной ширины
- **THEN** имя переносится или сокращается с доступным полным названием без изменения фактического metadata
- **AND** progress и terminal actions не выходят за границы карточки

#### Scenario: Доступно действие cancellation

- **WHEN** item находится в queued или active state и может быть отменён
- **THEN** карточка показывает заметное, но не доминирующее destructive действие «Отменить»
- **AND** «Повторить», «Открыть» и «Сохранить» не отображаются до применимого состояния

#### Scenario: Transfer завершён успешно

- **WHEN** verification завершена и item становится completed
- **THEN** карточка показывает текстовый success status и применимые действия «Открыть» или «Сохранить»
- **AND** progress не остаётся в active animation и не сообщает «проверяется»
