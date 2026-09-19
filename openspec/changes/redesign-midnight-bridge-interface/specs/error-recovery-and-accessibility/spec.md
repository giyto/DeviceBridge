## MODIFIED Requirements

### Requirement: Visual hierarchy остаётся согласованной во всех состояниях

Android и web UI MUST использовать один смысл status colors, typography hierarchy и primary/secondary/destructive actions для connection, pairing, text, file, history и settings в Midnight dark и Porcelain light themes. Loading, empty, error, success и cancellation surfaces MUST сохранять структуру экрана и MUST NOT вызывать неожиданный layout shift, потерю focus или появление действия за границами контейнера. Theme selection, скрытие и повторный показ security warning MUST быть доступны с клавиатуры и вспомогательных технологий и MUST NOT изменять operation или security state.

#### Scenario: Content сменяется error state

- **WHEN** операция переходит из loading или active state в recoverable error
- **THEN** причина и primary recovery action появляются в той же смысловой области без полной перестройки навигации
- **AND** focus остаётся на устойчивом элементе либо предсказуемо перемещается к error summary

#### Scenario: Empty state показан впервые

- **WHEN** подтверждённый источник данных возвращает пустой результат
- **THEN** UI показывает короткое объяснение и не более одного полезного primary action
- **AND** не использует декоративную иллюстрацию как замену тексту или доступному имени

#### Scenario: Theme меняется при открытом content

- **WHEN** пользователь или система меняет эффективную light/dark theme во время pairing, draft или active transfer state
- **THEN** content, focus и operation state сохраняются без повторной команды или layout jump
- **AND** status остаётся различимым текстом, формой и контрастом, а не только новым цветом

#### Scenario: Security warning скрывается или возвращается

- **WHEN** пользователь скрывает либо повторно показывает предупреждение доверенной сети
- **THEN** focus перемещается предсказуемо, а live region сообщает изменение не более одного раза
- **AND** warning preference не влияет на authorization, trusted browser или server lifecycle
