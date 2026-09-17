## ADDED Requirements

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

## MODIFIED Requirements

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
