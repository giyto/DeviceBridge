## MODIFIED Requirements

### Requirement: Android shell использует единую визуальную систему DeviceBridge

Android UI MUST использовать согласованную систему `Midnight Bridge / Porcelain Bridge`: semantic design tokens для light/dark colors, typography, spacing по сетке 4/8, shape, border, elevation и status meanings. Тёмная тема MUST использовать спокойную near-black/graphite основу с ограниченным сине-фиолетовым accent, а светлая — холодный нейтральный фон и белые рабочие surfaces без чисто белого page background. Primary, secondary и destructive actions MUST иметь стабильную иерархию и состояния default, pressed, focused, disabled, loading, error и success. Визуальная система MUST сохранять Material platform conventions, MUST NOT зависеть от внешних ресурсов и MUST NOT использовать glow, blur или motion как единственный признак состояния.

#### Scenario: Пользователь переключает системную тему

- **WHEN** системная тема меняется между светлой и тёмной
- **THEN** все top-level screens используют соответствующие Midnight или Porcelain surfaces, typography и status colors без вспышки неподходящей темы
- **AND** status, border и focus остаются понятными без опоры только на цвет

#### Scenario: Primary action временно недоступен

- **WHEN** действие нельзя выполнить из-за loading, отсутствия session или незавершённой validation
- **THEN** control имеет понятный disabled state и сохраняет label
- **AND** рядом доступно краткое объяснение причины, если она не очевидна из контекста

#### Scenario: Системная анимация уменьшена

- **WHEN** Android сообщает уменьшенный или отключённый animation scale
- **THEN** декоративный Signal Flow feedback сокращается или становится статическим
- **AND** изменение connection или transfer state остаётся немедленно понятным по тексту и semantics

## ADDED Requirements

### Requirement: Android screens используют спокойную premium-композицию

Home, Text, Files, History и Settings SHALL использовать единый content rhythm, ограниченное количество уровней surfaces и предсказуемое расположение primary actions. Декоративная вложенность карточек MUST быть исключена, а мотив Signal Flow MAY применяться только для краткого объяснения активной связи или передачи. Редизайн MUST сохранять все существующие команды, drafts, operation identifiers, navigation destinations и recovery actions.

#### Scenario: Home показывает активную связь

- **WHEN** server запущен и browser session активна
- **THEN** connection state, browser identity и основные действия образуют первый визуальный уровень
- **AND** декоративный connection indicator не вытесняет address, pairing или active transfer information

#### Scenario: Text или Files содержат плотное содержимое

- **WHEN** feed содержит длинные URL, несколько transfer items или длинные filenames
- **THEN** surfaces сохраняют выравнивание, читаемость и применимые actions без лишних вложенных карточек
- **AND** изменение внешнего вида не меняет порядок или идентичность пользовательских операций

#### Scenario: History или Settings открыты в служебном состоянии

- **WHEN** экран показывает loading, empty, save-in-progress или recoverable error
- **THEN** визуальная иерархия остаётся согласованной с рабочим content state
- **AND** primary action остаётся один и не конкурирует с secondary controls
