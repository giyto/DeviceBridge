## ADDED Requirements

### Requirement: Android shell объясняет первый запуск и подключение компьютера

При первом открытии и при отсутствии запущенного сервера Android-приложение SHALL объяснять, что DeviceBridge работает локально, требует одной достижимой локальной сети и доверенного окружения, а отдельная desktop-программа и интернет не нужны. После запуска сервера shell MUST показывать актуальный адрес, pairing code и последовательность действий в browser без раскрытия session secret.

#### Scenario: Приложение открыто впервые

- **WHEN** пользователь впервые открывает DeviceBridge
- **THEN** shell кратко объясняет локальный принцип работы, доверенную сеть и шаг запуска сервера
- **AND** пользователь может продолжить без регистрации, покупки или настройки внешнего backend

#### Scenario: Сервер готов к подключению

- **WHEN** server lifecycle находится в Running и browser sessions отсутствуют
- **THEN** Home показывает достижимый адрес, pairing code и шаг подтверждения browser на телефоне
- **AND** данные остаются читаемыми при увеличенном шрифте без горизонтального скролла всего экрана

#### Scenario: Компьютер не подключается

- **WHEN** пользователь открывает помощь подключения из offline или connection error state
- **THEN** shell предлагает проверить одну сеть, актуальный IP, состояние сервера и VPN или network isolation
- **AND** не обещает подключение через интернет или мобильный cloud relay

### Requirement: Android recovery surfaces доступны на phone и large screens

Home, Text, Files, History и Settings MUST показывать согласованные loading, empty, disabled, success, cancelled и error states с доступными названиями, ролью и действием восстановления. Основная информация и primary action MUST оставаться доступными на phone и large-screen layout при font scale до 200 процентов, в светлой и тёмной теме и при использовании TalkBack.

#### Scenario: Ошибка открыта с TalkBack

- **WHEN** TalkBack фокусируется на error surface
- **THEN** пользователь слышит краткую причину, состояние и доступное действие восстановления в логичном порядке
- **AND** декоративные изображения и повторяющийся текст не озвучиваются как отдельные действия

#### Scenario: Activity пересоздана во время recoverable состояния

- **WHEN** Android пересоздаёт Activity после rotation, theme change или memory pressure
- **THEN** shell восстанавливает текущий экран, безопасный draft и применимое recovery state из источника истины
- **AND** не запускает server, pairing, transfer или retry повторно только из-за пересоздания UI

#### Scenario: Экран открыт на large-screen устройстве

- **WHEN** доступная ширина позволяет показать навигацию и содержимое рядом
- **THEN** shell использует large-screen компоновку без дублирования primary actions
- **AND** порядок semantics остаётся логичным и соответствует направлению чтения

### Requirement: Android shell использует единую визуальную систему DeviceBridge

Android UI MUST использовать согласованные design tokens для light/dark colors, typography, spacing по сетке 4/8, shape, border, elevation и status semantics. Primary, secondary и destructive actions MUST иметь стабильную иерархию и состояния default, pressed, focused, disabled, loading, error и success. Визуальная система MUST сохранять Material platform conventions и MUST NOT зависеть от внешних ресурсов.

#### Scenario: Пользователь переключает системную тему

- **WHEN** системная тема меняется между светлой и тёмной
- **THEN** все top-level screens используют согласованные surfaces, typography и status colors без вспышки неподходящей темы
- **AND** статус остаётся понятным без опоры только на цвет

#### Scenario: Primary action временно недоступен

- **WHEN** действие нельзя выполнить из-за loading, отсутствия session или незавершённой validation
- **THEN** control имеет понятный disabled state и сохраняет label
- **AND** рядом доступно краткое объяснение причины, если она не очевидна из контекста

### Requirement: Home является адаптивным connection dashboard

Home MUST показывать status surface как первый визуальный приоритет, отдельный компактный блок актуального address и pairing code, connected browsers, quick actions и active transfers. Инструкции подключения MUST сворачиваться после успешной browser session и оставаться доступными по явному действию. Top-level navigation MUST использовать bottom navigation на phone и navigation rail на large screen без дублирования destinations.

#### Scenario: Сервер запущен без browser session

- **WHEN** пользователь открывает Home при Running server и отсутствии sessions
- **THEN** status surface, address, pairing code и следующий шаг считываются раньше secondary content
- **AND** copy actions не перекрывают длинный адрес или code при font scale 200 процентов

#### Scenario: Browser успешно подключён

- **WHEN** первая browser session становится active
- **THEN** подробная инструкция сворачивается, а connected browser и quick actions становятся основным рабочим содержимым
- **AND** пользователь может повторно открыть инструкцию без остановки сервера

#### Scenario: Ширина меняется между phone и large screen

- **WHEN** доступная ширина пересекает adaptive breakpoint
- **THEN** bottom navigation заменяется navigation rail с теми же destinations и selection state
- **AND** screen content и active operation не пересоздаются как новая пользовательская команда

### Requirement: Text и Files используют устойчивые operational surfaces

Text screen MUST показывать session feed с различимыми направлениями, отправителем, временем, типом и status, а composer MUST оставаться в предсказуемой доступной области. File screen MUST показывать type icon, имя, размер, направление, stage/progress и применимые actions внутри границ item card. Длинное содержимое MUST переноситься или обрезаться предсказуемо без перекрытия controls.

#### Scenario: Получена длинная ссылка

- **WHEN** text item содержит длинный URL без пробелов
- **THEN** карточка переносит или безопасно сокращает отображение без горизонтального overflow
- **AND** действия «Копировать» и «Открыть» остаются внутри карточки и доступны TalkBack

#### Scenario: File transfer меняет состояние

- **WHEN** item проходит queued, transferring, verifying и terminal state
- **THEN** одна карточка обновляет текстовый stage, progress и набор применимых actions без layout jump
- **AND** скорость и remaining time показываются только при наличии достоверной оценки
