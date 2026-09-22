## MODIFIED Requirements

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
