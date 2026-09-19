# local-history-and-settings Specification

## Purpose

Определить локальную постоянную историю операций и пользовательские настройки DeviceBridge, которые хранятся только на Android host и делают ежедневное использование предсказуемым без внешнего backend.

## Requirements

### Requirement: Terminal операции записываются в локальную историю

DeviceBridge MUST создавать одну локальную history record для каждой text, link или file operation, достигшей terminal результата. Запись MUST содержать идентификатор, тип, направление, безопасную browser label, время, итоговый статус и относящиеся к типу безопасные metadata, но MUST NOT копировать file content или полный text payload.

#### Scenario: Файл успешно передан
- **WHEN** file transfer получает completed после проверки целостности
- **THEN** история сохраняет display name, MIME type, размер, направление, время, SHA-256 и completed
- **AND** не сохраняет содержимое файла или доступный другой session source URI

#### Scenario: Файловая операция отменена или завершилась ошибкой
- **WHEN** file transfer получает cancelled или failed
- **THEN** история сохраняет terminal status и безопасную пользовательскую причину
- **AND** не считает partial output завершённым файлом

#### Scenario: Текстовая операция завершена
- **WHEN** text или link operation получает delivered либо failed
- **THEN** история сохраняет тип, направление, время, status и plain-text preview не длиннее 200 Unicode code points
- **AND** полный text payload не сохраняется

### Requirement: Пользователь просматривает и фильтрует историю

Android-приложение MUST показывать history records от новых к старым, предоставлять детали записи и фильтры по направлению, типу и terminal status. History UI MUST иметь честное empty state и MUST NOT показывать данные других источников или тестовые записи.

#### Scenario: История содержит разные операции
- **WHEN** пользователь открывает раздел «История»
- **THEN** записи отображаются от новых к старым с типом, направлением, временем и status
- **AND** пользователь может отфильтровать их по направлению, типу и status

#### Scenario: Открыты детали файла
- **WHEN** пользователь выбирает file history record
- **THEN** Android показывает сохранённые имя, MIME type, размер, checksum, browser label, время и результат
- **AND** не обещает открыть файл, если доступный content URI не был безопасно сохранён

#### Scenario: Подходящих записей нет
- **WHEN** история пуста либо активный фильтр не имеет результатов
- **THEN** Android показывает различимое empty state
- **AND** не подменяет его ошибкой загрузки

### Requirement: Историю можно удалить и ограничить сроком хранения

Пользователь MUST иметь возможность удалить отдельную record или очистить всю историю. DeviceBridge MUST автоматически удалять records старше настроенного срока хранения, равного 30 дням по умолчанию и выбираемого пользователем в диапазоне от 1 до 365 дней. Удаление history record MUST NOT удалять переданный пользовательский файл.

#### Scenario: Удалена отдельная запись
- **WHEN** пользователь подтверждает удаление одной history record
- **THEN** запись исчезает из истории
- **AND** исходный или полученный файл остаётся без изменений

#### Scenario: История очищена полностью
- **WHEN** пользователь подтверждает действие «Очистить историю»
- **THEN** все history records удаляются локально
- **AND** текущие active transfers и пользовательские файлы не отменяются и не удаляются

#### Scenario: Истёк срок хранения
- **WHEN** запись старше текущего retention period и выполняется плановая очистка
- **THEN** запись удаляется без обращения к внешнему сервису
- **AND** более новые записи сохраняются

### Requirement: Настройки сохраняются локально и валидируются

Android-приложение MUST хранить имя телефона, retention period, выбранную папку сохранения и пользовательский file size limit между перезапусками процесса. Имя MUST быть непустым после trim, не длиннее 40 Unicode code points и не содержать управляющих символов. File size limit MUST быть больше нуля и MUST NOT превышать hard limit 1 073 741 824 байта.

#### Scenario: Пользователь меняет имя телефона
- **WHEN** введено допустимое имя и пользователь сохраняет настройку
- **THEN** Android и последующие browser sessions показывают нормализованное имя
- **AND** значение сохраняется после пересоздания Activity и процесса

#### Scenario: Указан недопустимый лимит
- **WHEN** пользователь пытается сохранить нулевой лимит или значение выше 1 ГиБ
- **THEN** настройка отклоняется с понятным сообщением
- **AND** прежнее допустимое значение продолжает действовать

#### Scenario: Настройки ещё не создавались
- **WHEN** приложение впервые читает settings storage
- **THEN** срок хранения равен 30 дням, file limit равен проверенному hard limit и custom destination отсутствует
- **AND** экран не показывает вымышленные trusted browsers

### Requirement: Папка сохранения использует ограниченный SAF-доступ

Пользователь MUST выбирать default destination через системный Storage Access Framework. DeviceBridge MAY сохранять выданный persistable URI permission только для выбранной папки и MUST проверять его перед каждой Browser → Android передачей; широкое storage permission запрещено.

#### Scenario: Пользователь выбирает папку по умолчанию
- **WHEN** системный picker возвращает доступную tree URI и пользователь подтверждает настройку
- **THEN** DeviceBridge сохраняет только URI и выданный scoped permission
- **AND** следующие входящие offers могут явно использовать эту папку без повторного picker

#### Scenario: Выбор папки отменён
- **WHEN** пользователь закрывает picker без результата
- **THEN** предыдущая допустимая destination остаётся без изменений
- **AND** настройка не заменяется пустым или недоступным URI

#### Scenario: Доступ к папке отозван
- **WHEN** сохранённый URI больше нельзя открыть
- **THEN** DeviceBridge помечает destination недоступной и просит выбрать папку снова
- **AND** не начинает входящий file payload и не завершается с crash

### Requirement: Trusted browsers управляются только с Android host

Android-приложение MUST показывать список действующих trusted browsers с нормализованной label, датой создания, последним использованием и сроком действия. Пользователь MUST иметь возможность отозвать один trusted browser или все credentials; секретное значение MUST храниться на Android в защищённом виде, не отображаться и не попадать в backup или журналы.

#### Scenario: Browser получил доверие
- **WHEN** пользователь на Android явно разрешает запомнить подтверждаемый browser
- **THEN** список settings показывает его безопасные metadata и дату истечения
- **AND** credential secret не отображается

#### Scenario: Пользователь отзывает один browser
- **WHEN** пользователь подтверждает отзыв выбранного trusted browser
- **THEN** его credential и полученные через него active sessions немедленно становятся недействительными
- **AND** остальные trusted browsers сохраняют доступ

#### Scenario: Пользователь отзывает все browsers
- **WHEN** пользователь подтверждает действие «Отозвать все»
- **THEN** все trusted credentials и производные sessions становятся недействительными
- **AND** новое подключение снова требует pairing code и решения Android host

### Requirement: История и настройки остаются локальными

History records и обычные settings MUST быть доступны только Android UI и MUST NOT публиковаться через browser API. DeviceBridge MUST NOT обращаться к внешнему backend для синхронизации, аналитики, резервной копии или восстановления этих данных.

#### Scenario: Browser запрашивает history или settings
- **WHEN** LAN client запрашивает незаявленный history либо settings route
- **THEN** production server возвращает 404 независимо от session token
- **AND** не раскрывает факт существования записей или значения настроек

#### Scenario: Интернет недоступен
- **WHEN** пользователь просматривает историю или изменяет настройки без интернет-соединения
- **THEN** все операции работают локально
- **AND** приложение не создаёт внешний сетевой запрос

### Requirement: History и Settings различают loading, empty и persistence error

History и Settings MUST показывать initial loading до первого результата persistence, отдельное empty state при успешном пустом результате и error state при неуспешном чтении. Ошибка чтения или записи MUST NOT подменяться пустым списком, default success либо вымышленными данными.

#### Scenario: History успешно пуста

- **WHEN** repository завершил загрузку и не нашёл terminal records
- **THEN** History показывает подтверждённое empty state
- **AND** retry не отображается как обязательное действие

#### Scenario: History не удалось загрузить

- **WHEN** persistence возвращает ошибку чтения
- **THEN** History показывает error state и явный retry
- **AND** не сообщает пользователю, что операций ещё не было

#### Scenario: Settings не удалось сохранить

- **WHEN** пользователь подтверждает допустимое значение, но persistence не завершает запись
- **THEN** UI сообщает об ошибке, сохраняет последнее успешно persisted значение как активное и оставляет введённый draft для исправления или retry
- **AND** не показывает ложное уведомление об успешном сохранении

### Requirement: Недоступный SAF destination восстанавливается без потери других данных

При отзыве persistable permission, удалении provider или недоступности выбранной SAF-папки DeviceBridge MUST обозначить destination как недоступный, прекратить использовать его для новых записей и предложить выбрать папку снова. Ошибка destination MUST NOT удалять history, trusted browsers, другие settings или независимые file drafts.

#### Scenario: Persistable permission отозвано

- **WHEN** приложение больше не может открыть сохранённый tree URI
- **THEN** Settings показывает недоступный destination и действие «Выбрать папку»
- **AND** browser upload ожидает нового destination вместо записи в неразрешённое место

#### Scenario: Повторный picker отменён

- **WHEN** пользователь закрывает folder picker без выбора
- **THEN** прежний destination остаётся помеченным недоступным, а pending offer сохраняется, если source и session ещё действуют
- **AND** history и остальные settings не изменяются

#### Scenario: Новая папка успешно выбрана

- **WHEN** пользователь выдаёт persistable access к новому tree URI
- **THEN** DeviceBridge валидирует доступ и только после этого делает папку активной
- **AND** pending item может быть продолжен явным действием пользователя без создания дубликата

### Requirement: History и Settings визуально поддерживают быстрое сканирование

History MUST использовать спокойную плотность списка и различать direction, type и status без избыточных вложенных decorative cards. Empty state MUST кратко объяснять отсутствие данных и показывать не более одного полезного primary action, если оно применимо. Settings MUST группировать поля в логические sections, показывать validation рядом с проблемным control и визуально различать draft, saving, saved и failed save.

#### Scenario: History содержит разные операции

- **WHEN** список содержит text и file records разных направлений и статусов
- **THEN** пользователь может различить основные metadata без открытия каждой записи
- **AND** status передаётся текстом и семантикой, а не только цветом или иконкой

#### Scenario: History пуста

- **WHEN** подтверждённый repository result не содержит records
- **THEN** empty state показывает короткое объяснение без фиктивных данных
- **AND** предлагает одно релевантное действие либо не показывает action, если оно не требуется

#### Scenario: Settings save не завершён

- **WHEN** допустимый draft сохраняется либо запись завершилась ошибкой
- **THEN** control и section показывают saving или failed state рядом с изменённым значением
- **AND** весь экран не блокируется общей modal error без необходимости
