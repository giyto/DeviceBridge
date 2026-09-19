## ADDED Requirements

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
