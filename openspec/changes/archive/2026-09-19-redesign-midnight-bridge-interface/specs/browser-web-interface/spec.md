## MODIFIED Requirements

### Requirement: Пользователь видит ограничение локального HTTP

Web shell MUST понятным языком сообщать, что локальный HTTP не шифрует трафик и DeviceBridge следует использовать только в доверенной домашней или личной сети. Предупреждение MUST быть раскрыто при первом использовании или после сброса preference и MAY быть свёрнуто в компактную строку нажатием по всей поверхности. Та же поверхность MUST раскрывать предупреждение обратно без отдельной кнопки и без дополнительного security help/details блока. Collapse preference MUST NOT изменять network restrictions, authorization или session security.

#### Scenario: Открыта корневая страница

- **WHEN** пользователь открывает web shell и сохранённого решения скрыть предупреждение нет
- **THEN** на странице видимо предупреждение о незашифрованном локальном соединении
- **AND** пользователю рекомендовано не передавать чувствительные данные через публичный или чужой Wi-Fi

#### Scenario: Пользователь сворачивает предупреждение

- **WHEN** пользователь нажимает по раскрытой поверхности предупреждения мышью, Enter или Space
- **THEN** поясняющий текст сворачивается, а compact surface сохраняет warning icon, заголовок и chevron
- **AND** control сохраняет focus, получает `aria-expanded="false"` и остаётся компактным после reload и полного перезапуска того же browser profile

#### Scenario: Пользователь раскрывает предупреждение

- **WHEN** пользователь повторно активирует компактную поверхность предупреждения
- **THEN** полный поясняющий текст появляется в той же карточке без отдельного help/details блока
- **AND** control сохраняет focus и получает `aria-expanded="true"`

### Requirement: Интерфейс адаптируется к браузерному окну и выбранной теме

Web shell MUST сохранять читаемость и основные действия при ширине viewport от 360 до 1920 пикселей и MUST предоставлять только темы `Светлая` и `Тёмная`. Тема MUST переключаться одной icon button справа в connection area: солнце обозначает активную светлую тему, луна — активную тёмную. По умолчанию SHALL использоваться тёмная схема. Выбор MUST сохраняться локально в browser profile, применяться до первой видимой отрисовки, синхронизироваться между вкладками одного origin и не зависеть от server session или trusted credential. Ранее сохранённое значение `system`, неизвестное значение и недоступное storage MUST давать безопасный fallback `dark`.

#### Scenario: Узкое и широкое окно

- **WHEN** страница отображается при ширине 360, 768 или 1920 пикселей
- **THEN** основной контент не требует горизонтальной прокрутки
- **AND** connection status, theme control и primary workspace action остаются видимыми и читаемыми

#### Scenario: Пользователь переключает тему иконкой

- **WHEN** пользователь активирует кнопку солнца или луны мышью либо клавиатурой
- **THEN** страница немедленно переключается между светлой и тёмной темами без reload
- **AND** кнопка показывает иконку активной темы, а её accessible name сообщает действие переключения

#### Scenario: Пользователь возвращается на страницу

- **WHEN** пользователь ранее выбрал светлую или тёмную тему
- **THEN** выбранная тема остаётся после reload и полного перезапуска browser
- **AND** изменение системной темы не переопределяет выбор

#### Scenario: Страница открывается с сохранённой темой

- **WHEN** browser загружает web shell с ранее сохранённым допустимым theme preference
- **THEN** правильные color-scheme и surfaces применены до появления основного content
- **AND** пользователь не видит промежуточную вспышку противоположной темы

### Requirement: Web shell разделяет визуальный язык DeviceBridge с Android

Web UI MUST использовать согласованный с Android визуальный язык `Midnight Bridge / Porcelain Bridge`: одинаковый смысл semantic status colors, typography hierarchy, spacing rhythm, shape и action hierarchy при сохранении browser conventions и semantic HTML. Тёмная тема MUST использовать спокойную near-black/graphite layered foundation с ограниченным accent, а светлая — холодный светло-серый page background и белые рабочие surfaces; обе темы MUST иметь различимые default, hover, pressed, focused, disabled, loading, error и success states без внешних fonts, CDN или styling runtime.

#### Scenario: Пользователь меняет системную тему

- **WHEN** эффективная theme меняется между light и dark через icon toggle
- **THEN** page применяет согласованные tokens без потери текста, border, focus indicator или status distinction
- **AND** change не требует перезагрузки внешнего ресурса и не перестраивает смысловую структуру DOM

#### Scenario: Control получает keyboard focus

- **WHEN** пользователь перемещается по primary, secondary и destructive actions клавишей Tab
- **THEN** каждый focus state видим на соответствующем surface в обеих темах
- **AND** визуальный порядок и семантический порядок совпадают

### Requirement: Web shell адаптирует рабочую область без потери контекста

При достаточной ширине web shell SHALL показывать две согласованные области: connection/device context и text/files/activity workspace. Постоянная глобальная верхняя шапка с отдельными branding и locality badges MUST отсутствовать; branding, icon theme toggle и локальный connection context SHALL находиться внутри содержательных областей и не занимать отдельную полосу viewport. Декоративная подпись «Телефон и компьютер — рядом» и цветная верхняя линия состояния MUST отсутствовать. После авторизации connection status MUST показывать эффективное имя Android-устройства, полученное из защищённого session status, вместо технической версии web assets; до авторизации это поле MUST NOT раскрывать device name. На narrow viewport layout MUST переходить в одну колонку без horizontal page overflow, сохраняя connection status и текущую operation. Text/link feed и file cards MUST использовать те же directions, stages, terminal statuses и применимые actions, что Android UI.

#### Scenario: Wide desktop viewport

- **WHEN** viewport достаточно широк для двух колонок
- **THEN** product/theme area и connection/workspace area начинаются на одной верхней линии, а connection/device context остаётся видимым рядом с рабочей областью без дублирования primary actions и без постоянной верхней шапки
- **AND** ширина text/file content ограничена для читаемости

#### Scenario: Narrow viewport или zoom 200 процентов

- **WHEN** viewport равен 360 пикселям либо browser zoom установлен на 200 процентов
- **THEN** области выстраиваются в одну колонку, длинные address, filename и URL не создают horizontal page scroll
- **AND** theme control, composer, progress и terminal actions остаются доступны с клавиатуры

#### Scenario: Верхняя часть страницы отображается после редизайна

- **WHEN** пользователь открывает страницу на любом поддерживаемом viewport
- **THEN** перед connection/device content отсутствует отдельная полоса с логотипом и меткой «Только локально»
- **AND** отсутствуют декоративная подпись и цветная верхняя линия, но сохраняются page title, доступное имя продукта и локальная security guidance
#### Scenario: Авторизованный браузер показывает подключённый телефон

- **WHEN** browser session подтверждена и защищённый status содержит effective device name
- **THEN** connection card показывает это имя рядом с подписью «Android-устройство»
- **AND** технический hash web assets не занимает пользовательскую строку состояния
