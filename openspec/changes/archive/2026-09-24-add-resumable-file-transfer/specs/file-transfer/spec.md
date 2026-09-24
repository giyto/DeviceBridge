## MODIFIED Requirements

### Requirement: Browser download завершается без повторного выбора файла

Android → Browser item MUST оставаться предложением до явного действия пользователя в web UI. На LAN HTTP web shell MUST запускать нативную загрузку без помещения полного response в память. После успешной отдачи заявленного количества байтов server MUST установить completed и MUST NOT требовать повторного выбора скачанного файла. Download endpoint MUST объявлять поддержку byte ranges и сильный validator содержимого. Прерванную загрузку MUST быть возможно продолжить ranged-запросом того же item в том же server generation в течение 15 минут после обрыва, пока исходный source не изменился.

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
- **THEN** item переходит в failed с сохранённым числом доставленных байтов, а очередь освобождается для следующего item
- **AND** web UI объясняет, что загрузку можно возобновить в менеджере загрузок браузера или повторить с начала без повторного выбора исходного файла

#### Scenario: Браузер возобновляет загрузку

- **WHEN** в том же server generation и в течение 15 минут после обрыва браузер запрашивает оставшийся диапазон того же item с совпадающим validator
- **THEN** server отдаёт только запрошенный оставшийся диапазон, а item возвращается в transferring с progress от уже доставленных байтов
- **AND** после отдачи последнего байта item становится completed

#### Scenario: Продолжение загрузки невозможно

- **WHEN** ranged-запрос приходит после окна продолжения, в другом server generation, для отменённого item, с несовпадающим validator или после изменения source
- **THEN** server отклоняет запрос без отдачи содержимого
- **AND** item остаётся failed, а пользователь может явно повторить загрузку с начала

### Requirement: Cancellation освобождает ресурсы и partial output

Пользователь MUST иметь возможность отменить queued или active transfer с Android и из принадлежащей browser session. Cancellation, network failure, session revoke и server stop MUST закрывать streams, file descriptors и coroutine jobs и освобождать очередь. Явная отмена MUST удалять partial output, если provider это допускает. При network failure, потере session или server stop Browser → Android upload размером не меньше 8 МиБ MUST сохранять уже записанную часть как незавершённую для последующего продолжения. Меньшие uploads MUST удалять partial output, если provider это допускает.

#### Scenario: Отменён queued item

- **WHEN** пользователь отменяет item до начала потока
- **THEN** item удаляется из executable queue и становится cancelled
- **AND** сетевое тело или output не открывается

#### Scenario: Пользователь повторяет отменённый или failed item

- **WHEN** владеющая session явно запрашивает retry в том же server generation
- **THEN** тот же item с сохранёнными metadata возвращается в FIFO с progress, равным числу байтов сохранённой незавершённой части, или с нулевым progress, если такой части нет
- **AND** доступный Android source сохраняется до completed, revoke или server stop, а дубликат item не создаётся

#### Scenario: Отменён active upload

- **WHEN** пользователь отменяет Browser → Android transfer
- **THEN** входящий stream и output закрываются
- **AND** partial document и запись о незавершённой части удаляются либо partial ясно помечается незавершённым, если provider не допускает удаления

#### Scenario: Upload прерван сетью

- **WHEN** поток Browser → Android upload размером не меньше 8 МиБ обрывается из-за network failure, потери session или остановки сервера
- **THEN** stream и file descriptor закрываются, а уже записанные байты остаются в незавершённом partial document выбранной папки
- **AND** partial не получает итоговое имя и не показывается как completed file

#### Scenario: Server остановлен

- **WHEN** lifecycle покидает Running
- **THEN** все незавершённые transfers получают конечное состояние отмены или ошибки
- **AND** новый server generation не восстанавливает payload или очередь прошлого generation, а сохранённая незавершённая часть используется только новой явно предложенной и подтверждённой operation

### Requirement: File failure сохраняет корректную очередь и безопасный retry

File transfer UI MUST различать queued, connecting, transferring, verifying, completed, cancelled, failed и retrying без возврата terminal item в active state самопроизвольно. Network loss, destination unavailable, insufficient space, checksum mismatch, file limit, source unavailable, session revoke и protocol mismatch MUST иметь отдельные причины. Manual retry MUST сохранять исходный transfer identifier и доступный source только для повторения той же неизменённой операции. Если для item есть сохранённая незавершённая часть, retry MUST продолжать передачу с её конца и MUST быть обозначен в UI как продолжение с указанием уже переданного объёма.

#### Scenario: Active transfer завершился сетевой ошибкой

- **WHEN** поток обрывается до terminal result и source всё ещё доступен в том же server generation
- **THEN** item становится failed, очередь освобождается для следующего item и UI показывает manual retry либо «Продолжить», если сохранена незавершённая часть
- **AND** transfer не перезапускается и не продолжается автоматически после reconnect

#### Scenario: Verification не прошла

- **WHEN** итоговый checksum не совпадает с заявленным
- **THEN** item становится failed с причиной checksum mismatch
- **AND** partial или повреждённый output и запись о незавершённой части удаляются, а следующий retry начинается с нулевого progress

#### Scenario: Source изменился или недоступен

- **WHEN** перед retry исходный source больше недоступен либо metadata не совпадают с исходной операцией
- **THEN** retry отклоняется как terminal для этого item
- **AND** UI предлагает выбрать файл заново и создать новую operation

#### Scenario: Retry той же операции подтверждён

- **WHEN** пользователь повторяет failed transfer с тем же доступным source и metadata
- **THEN** item возвращается в FIFO с исходным transfer identifier и progress от конца сохранённой незавершённой части либо с нуля
- **AND** дубликат queue item и скрытая перезапись completed output не создаются

#### Scenario: Сохранённая часть потеряна до продолжения

- **WHEN** пользователь выбирает «Продолжить», но незавершённая часть удалена, недоступна или короче записанного значения
- **THEN** передача начинается с фактически сохранённого префикса либо с нуля, если префикса нет
- **AND** UI не показывает progress больше фактически принятого объёма

### Requirement: File transfer не становится постоянной историей

DeviceBridge MUST сохранять в локальной Android history только безопасные metadata terminal file operation: transfer identifier, display name, MIME type, размер, направление, время, browser label, checksum при наличии и terminal status. Содержимое, executable queue, download grants и source URI MUST оставаться только в границах текущего server generation или выбранного пользовательского storage и MUST NOT копироваться в history или Android backup. Запись о незавершённой части для продолжения прерванного upload MUST храниться только в app-private storage и MUST содержать только SHA-256 и размер файла, display name, MIME type, ссылку на partial document в выбранной папке, число сохранённых байтов и время последнего изменения. Такая запись MUST NOT попадать в history или Android backup и MUST удаляться вместе с незавершённой частью.

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
- **AND** terminal history records и записи о незавершённых частях в пределах срока хранения сохраняются до явного удаления или cleanup

#### Scenario: History storage недоступен
- **WHEN** запись terminal metadata временно не удалась
- **THEN** фактический transfer result и целостность пользовательского файла не изменяются
- **AND** Android показывает отдельную безопасную ошибку истории без повторной передачи payload

## ADDED Requirements

### Requirement: Browser upload продолжается с сохранённого места

Offer Browser → Android upload MUST сопоставляться с сохранёнными незавершёнными частями по SHA-256, размеру и display name. Если совпадающая часть существует и offer подтверждён, вручную или автоприёмом, в ту же папку, в которой лежит эта часть, server MUST сообщить браузеру offset продолжения. Браузер MUST передать только байты начиная с этого offset. Перед приёмом payload server MUST восстановить контрольную сумму по фактически сохранённому префиксу и MUST проверить SHA-256 всего файла после приёма последнего байта. Продолжение MUST NOT обходить подтверждение, лимиты размера, MIME и имени, очередь и правила trusted session.

#### Scenario: Тот же файл отправлен после перезапуска сервера

- **WHEN** после остановки и нового запуска сервера пользователь снова отправляет файл с тем же SHA-256, размером и именем и подтверждает его в ту же папку
- **THEN** передача начинается с конца сохранённой части, а UI обеих сторон показывает продолжение с уже сохранённым объёмом
- **AND** после приёма всех байтов и совпадения SHA-256 файл получает итоговое имя и item становится completed

#### Scenario: Продолжение в той же вкладке

- **WHEN** upload в той же session и том же server generation прервался и пользователь выбрал «Продолжить»
- **THEN** браузер передаёт только оставшиеся байты того же transfer identifier
- **AND** item не требует повторного подтверждения на Android, пока выбранная папка остаётся доступной

#### Scenario: Подтверждение в другую папку

- **WHEN** совпадающая незавершённая часть существует, но пользователь принимает offer в другую папку
- **THEN** передача начинается с нуля в выбранной папке
- **AND** прежняя незавершённая часть остаётся до истечения срока хранения или удаления пользователем

#### Scenario: Файл изменился

- **WHEN** отправленный файл отличается от сохранённой части SHA-256, размером или именем
- **THEN** offer не использует сохранённую часть
- **AND** передача начинается с нуля без изменения чужой незавершённой части

#### Scenario: Сохранённый префикс повреждён

- **WHEN** после продолжения итоговый SHA-256 не совпадает с заявленным
- **THEN** item становится failed с причиной checksum mismatch, а незавершённая часть удаляется
- **AND** следующая попытка начинается с нуля

#### Scenario: Provider не поддерживает запись с позиции

- **WHEN** выбранная папка не позволяет продолжить запись в существующий partial document
- **THEN** передача начинается с нуля в новом partial document
- **AND** пользователь не получает completed file с пропущенными байтами

### Requirement: Незавершённые части ограничены и управляемы

DeviceBridge MUST хранить незавершённую часть upload не дольше 24 часов с момента последнего изменения. Незавершённая часть MUST удаляться при явной отмене, успешном завершении, истечении срока или по команде пользователя. Settings MUST показывать число и общий размер незавершённых частей и MUST давать действие удалить их все. Незавершённая часть MUST NOT выглядеть как готовый пользовательский файл.

#### Scenario: Срок хранения истёк

- **WHEN** незавершённая часть не изменялась 24 часа
- **THEN** при ближайшем запуске приложения или сервера она и её запись удаляются
- **AND** последующий offer того же файла начинается с нуля

#### Scenario: Пользователь удаляет незавершённые части

- **WHEN** пользователь в Settings выбирает удаление незавершённых файлов
- **THEN** все partial documents и записи о них удаляются, а Settings показывает, что незавершённых файлов нет
- **AND** completed files и history records не изменяются

#### Scenario: Незавершённая часть удалена вне приложения

- **WHEN** пользователь удалил partial document в файловом менеджере или доступ к папке отозван
- **THEN** запись о части удаляется при ближайшей проверке без crash
- **AND** следующий offer того же файла начинается с нуля
