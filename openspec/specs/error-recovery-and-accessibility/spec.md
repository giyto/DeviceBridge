# error-recovery-and-accessibility Specification

## Purpose
Определяет единый наблюдаемый контракт ошибок, восстановления и доступности DeviceBridge, чтобы Android и browser UI одинаково и безопасно объясняли состояние и следующий шаг пользователя.

## Requirements

### Requirement: Пользовательская ошибка имеет единый безопасный контракт

Каждая user-facing ошибка DeviceBridge MUST содержать стабильный код причины, краткое объяснение простым языком, признак recoverable или terminal и ровно те действия восстановления, которые допустимы в текущем состоянии. Технические детали MUST быть отделены от основного сообщения и MUST NOT содержать pairing code, session token, trusted credential, полный путь, содержимое текста или файла, stack trace и иные секреты.

#### Scenario: Recoverable ошибка показана пользователю

- **WHEN** операция может быть безопасно продолжена после действия пользователя
- **THEN** Android или browser UI показывает причину и доступное действие восстановления
- **AND** состояние не обозначается как success или terminal failure до результата этого действия

#### Scenario: Terminal ошибка не допускает ложный retry

- **WHEN** исходные данные, session или server generation больше не позволяют повторить операцию
- **THEN** UI обозначает ошибку как terminal и не показывает неработающее действие retry
- **AND** предлагает начать новую операцию либо вернуться к предыдущему безопасному шагу

#### Scenario: Пользователь открывает технические детали

- **WHEN** для ошибки доступны диагностические сведения
- **THEN** UI показывает стабильный код и безопасный контекст отдельно от основного сообщения
- **AND** сведения не раскрывают секреты и пользовательское содержимое

### Requirement: Обязательные причины различаются и ведут к корректному recovery

DeviceBridge SHALL различать как минимум: разные локальные сети или недостижимый host, отказ или отзыв local-network permission, изменение IP-адреса, неверный или истёкший pairing code, ожидание или отказ подтверждения на телефоне, исчерпание pairing attempts, отзыв session или trusted browser, нехватку места, недоступную SAF-папку, потерю соединения, checksum mismatch, превышение file limit и неподдерживаемую protocol version.

#### Scenario: Телефон и компьютер недоступны друг другу

- **WHEN** browser не может достичь опубликованного локального endpoint либо соединение потеряно после смены сети
- **THEN** интерфейс предлагает проверить одну локальную сеть, актуальный адрес и состояние сервера
- **AND** не предлагает повторить передачу до восстановления session

#### Scenario: Разрешение или destination недоступны

- **WHEN** Android сообщает об отказе local-network permission, нехватке места или недоступной SAF-папке
- **THEN** интерфейс называет конкретную причину и предлагает открыть настройки либо выбрать доступную папку в зависимости от причины
- **AND** не маскирует ошибку общим сообщением о сети

#### Scenario: Pairing или protocol отклонены

- **WHEN** pairing code неверен, истёк, ожидает решения, отклонён, заблокирован лимитом попыток либо protocol version не поддерживается
- **THEN** browser показывает соответствующее состояние и только допустимый следующий шаг
- **AND** неподдерживаемая версия не приводит к обработке пользовательского payload

#### Scenario: Проверка файла завершилась ошибкой

- **WHEN** file size превышает лимит либо checksum не совпадает
- **THEN** transfer получает terminal failed state с точной безопасной причиной
- **AND** повреждённый или превышающий лимит output не выдаётся как completed

### Requirement: Автоматическое восстановление transport не повторяет пользовательскую операцию

DeviceBridge MUST отделять bounded reconnect канала событий от повторного выполнения text или file operation. Automatic reconnect MUST NOT повторно отправлять payload, подтверждать pairing, возобновлять отменённую операцию или создавать новый item. Manual retry MUST использовать исходный идемпотентный идентификатор, когда это повтор той же операции, и MUST создавать новый идентификатор только для новой пользовательской операции.

#### Scenario: Event channel временно оборван

- **WHEN** авторизованный канал событий теряет соединение, но session ещё может быть восстановлена
- **THEN** клиент выполняет ограниченную последовательность reconnect attempts
- **AND** queued, uncertain или failed операции не отправляются повторно автоматически

#### Scenario: Пользователь повторяет неопределённый результат

- **WHEN** пользователь выбирает retry после восстановления session для операции без terminal acknowledgement
- **THEN** DeviceBridge повторяет запрос с тем же operation identifier
- **AND** получатель не создаёт вторую доставку уже принятого payload

#### Scenario: Reconnect исчерпан

- **WHEN** ограниченная последовательность reconnect attempts завершилась без успеха
- **THEN** UI прекращает автоматические попытки и показывает понятные инструкции ручного восстановления
- **AND** сохранённые допустимые drafts остаются доступны пользователю

### Requirement: Состояния интерфейса честны и доступны

Android и web UI MUST явно различать initial loading, empty, ready, disabled, success, cancelled, offline и error. Статус и доступность действия MUST передаваться текстом и semantics, а не только цветом, положением или анимацией. Изменения соединения, transfer и pairing MUST быть доступны экранному диктору без многократного объявления каждого progress update.

#### Scenario: Данные ещё загружаются

- **WHEN** экран ожидает первый результат repository или session snapshot
- **THEN** UI показывает loading state и не показывает пустые данные как окончательный результат
- **AND** недоступные действия имеют объяснимое disabled state

#### Scenario: Операция отменена

- **WHEN** пользователь отменяет разрешённую операцию
- **THEN** UI показывает cancelled state и освобождает связанные действия очереди
- **AND** отмена не обозначается как success или failure

#### Scenario: Статус меняется для вспомогательной технологии

- **WHEN** connection, pairing или terminal operation state изменяется
- **THEN** screen reader получает краткое актуальное объявление
- **AND** частые progress updates не захватывают фокус и не создают непрерывный поток объявлений

### Requirement: Recovery и accessibility поддерживаются в целевой матрице

DeviceBridge MUST сохранять основные сценарии connection, recovery, cancellation и navigation на Android API 29 и API 37.1, в актуальных стабильных Chrome и Edge на Windows 11, при светлой и тёмной теме, увеличенном системном шрифте и browser viewport от 360 до 1920 пикселей.

#### Scenario: Android используется с увеличенным шрифтом

- **WHEN** системный font scale увеличен до 200 процентов на phone или large-screen устройстве
- **THEN** основное сообщение, причина ошибки и primary recovery action остаются читаемыми и доступными
- **AND** критичное действие не обрезается и не перекрывает другое содержимое

#### Scenario: Browser используется только с клавиатурой

- **WHEN** пользователь проходит connection и recovery flow клавишами Tab, Shift+Tab, Enter и Space при ширине 360, 768 или 1920 пикселей
- **THEN** порядок фокуса соответствует визуальному порядку и фокус всегда видим
- **AND** каждое доступное действие имеет понятное имя и может быть активировано без мыши

### Requirement: Visual hierarchy остаётся согласованной во всех состояниях

Android и web UI MUST использовать один смысл status colors, typography hierarchy и primary/secondary/destructive actions для connection, pairing, text, file, history и settings в Midnight dark и Porcelain light themes. Loading, empty, error, success и cancellation surfaces MUST сохранять структуру экрана и MUST NOT вызывать неожиданный layout shift, потерю focus или появление действия за границами контейнера. Icon theme toggle и единая сворачиваемая поверхность security warning MUST быть доступны с клавиатуры и вспомогательных технологий и MUST NOT изменять operation или security state. Иконка темы MUST иметь доступное имя, описывающее следующее действие, warning surface MUST сообщать `aria-expanded` и MUST NOT полагаться только на chevron, а History filter sheet MUST предоставлять heading, selectable state и доступные действия закрытия/сброса. Очистка focus тапом вне Android text field MUST NOT терять draft или перехватывать интерактивное действие.

#### Scenario: Content сменяется error state

- **WHEN** операция переходит из loading или active state в recoverable error
- **THEN** причина и primary recovery action появляются в той же смысловой области без полной перестройки навигации
- **AND** focus остаётся на устойчивом элементе либо предсказуемо перемещается к error summary

#### Scenario: Empty state показан впервые

- **WHEN** подтверждённый источник данных возвращает пустой результат
- **THEN** UI показывает короткое объяснение и не более одного полезного primary action
- **AND** не использует декоративную иллюстрацию как замену тексту или доступному имени

#### Scenario: Theme меняется при открытом content

- **WHEN** пользователь переключает эффективную light/dark theme во время pairing, draft или active transfer state
- **THEN** content, focus и operation state сохраняются без повторной команды или layout jump
- **AND** status остаётся различимым текстом, формой и контрастом, а не только новым цветом

#### Scenario: Security warning сворачивается или раскрывается

- **WHEN** пользователь сворачивает либо раскрывает предупреждение доверенной сети
- **THEN** focus остаётся на той же поверхности, а `aria-expanded` и доступное имя отражают новое состояние
- **AND** warning preference не влияет на authorization, trusted browser или server lifecycle
#### Scenario: History filter sheet доступен вспомогательным технологиям

- **WHEN** пользователь открывает фильтры History
- **THEN** screen reader получает заголовки групп, label и selected state каждой строки
- **AND** focus остаётся внутри modal sheet до закрытия и возвращается к кнопке фильтров после dismiss

#### Scenario: Android-клавиатура закрывается свободным тапом

- **WHEN** пользователь с активным text field нажимает по свободной области экрана
- **THEN** focus очищается без изменения введённого значения
- **AND** TalkBack semantics, scroll и клики по соседним controls сохраняются
