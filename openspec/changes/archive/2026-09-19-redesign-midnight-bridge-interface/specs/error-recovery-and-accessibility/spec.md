## MODIFIED Requirements

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
