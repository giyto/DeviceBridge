## MODIFIED Requirements

### Requirement: Пользователь видит ограничение локального HTTP

Web shell MUST понятным языком сообщать, что локальный HTTP не шифрует трафик и DeviceBridge следует использовать только в доверенной домашней или личной сети. Предупреждение MUST быть видимым при первом использовании или после сброса preference, MAY быть скрыто явным действием в текущем browser profile и MUST оставаться доступным для повторного показа через connection help. Скрытие предупреждения MUST NOT изменять network restrictions, authorization или session security.

#### Scenario: Открыта корневая страница

- **WHEN** пользователь открывает web shell и сохранённого решения скрыть предупреждение нет
- **THEN** на странице видимо предупреждение о незашифрованном локальном соединении
- **AND** пользователю рекомендовано не передавать чувствительные данные через публичный или чужой Wi-Fi

#### Scenario: Пользователь скрывает предупреждение

- **WHEN** пользователь активирует доступное действие «Скрыть» на предупреждении
- **THEN** предупреждение исчезает без изменения connection layout и остаётся скрытым после reload и полного перезапуска того же browser profile
- **AND** focus перемещается к устойчивому элементу connection area, а решение не считается согласием на небезопасную сеть

#### Scenario: Пользователь возвращает предупреждение

- **WHEN** пользователь выбирает действие повторного показа в connection help или локальная preference недоступна либо очищена
- **THEN** полное предупреждение снова отображается в логичном месте страницы
- **AND** им можно управлять с клавиатуры и screen reader

### Requirement: Интерфейс адаптируется к браузерному окну и системной теме

Web shell MUST сохранять читаемость и основные действия при ширине viewport от 360 до 1920 пикселей и MUST предоставлять режимы темы `Системная`, `Светлая` и `Тёмная`. По умолчанию SHALL использоваться системная схема. Явный выбор MUST сохраняться локально в browser profile, применяться до первой видимой отрисовки, синхронизироваться между вкладками одного origin и не зависеть от server session или trusted credential.

#### Scenario: Узкое и широкое окно

- **WHEN** страница отображается при ширине 360, 768 или 1920 пикселей
- **THEN** основной контент не требует горизонтальной прокрутки
- **AND** connection status, theme control и primary workspace action остаются видимыми и читаемыми

#### Scenario: Меняется системная цветовая схема

- **WHEN** выбран режим `Системная` и browser сообщает новую светлую или тёмную схему
- **THEN** страница применяет соответствующую тему без reload
- **AND** текст, status и интерактивные элементы сохраняют достаточный контраст

#### Scenario: Пользователь выбирает явную тему

- **WHEN** пользователь выбирает `Светлая` или `Тёмная`
- **THEN** выбранная тема применяется немедленно и остаётся после reload и полного перезапуска browser
- **AND** последующее изменение системной темы не переопределяет явный выбор

#### Scenario: Страница открывается с сохранённой темой

- **WHEN** browser загружает web shell с ранее сохранённым допустимым theme preference
- **THEN** правильные color-scheme и surfaces применены до появления основного content
- **AND** пользователь не видит промежуточную вспышку противоположной темы

### Requirement: Web shell разделяет визуальный язык DeviceBridge с Android

Web UI MUST использовать согласованный с Android визуальный язык `Midnight Bridge / Porcelain Bridge`: одинаковый смысл semantic status colors, typography hierarchy, spacing rhythm, shape и action hierarchy при сохранении browser conventions и semantic HTML. Тёмная тема MUST использовать спокойную near-black/graphite layered foundation с ограниченным accent, а светлая — холодный светло-серый page background и белые рабочие surfaces; обе темы MUST иметь различимые default, hover, pressed, focused, disabled, loading, error и success states без внешних fonts, CDN или styling runtime.

#### Scenario: Пользователь меняет системную тему

- **WHEN** эффективная theme меняется между light и dark из-за ручного или системного выбора
- **THEN** page применяет согласованные tokens без потери текста, border, focus indicator или status distinction
- **AND** change не требует перезагрузки внешнего ресурса и не перестраивает смысловую структуру DOM

#### Scenario: Control получает keyboard focus

- **WHEN** пользователь перемещается по primary, secondary и destructive actions клавишей Tab
- **THEN** каждый focus state видим на соответствующем surface в обеих темах
- **AND** визуальный порядок и семантический порядок совпадают

### Requirement: Web shell адаптирует рабочую область без потери контекста

При достаточной ширине web shell SHALL показывать две согласованные области: connection/device context и text/files/activity workspace. Постоянная глобальная верхняя шапка с отдельными branding и locality badges MUST отсутствовать; branding, theme control и локальный connection context SHALL находиться внутри содержательных областей и не занимать отдельную полосу viewport. На narrow viewport layout MUST переходить в одну колонку без horizontal page overflow, сохраняя connection status и текущую operation. Text/link feed и file cards MUST использовать те же directions, stages, terminal statuses и применимые actions, что Android UI.

#### Scenario: Wide desktop viewport

- **WHEN** viewport достаточно широк для двух колонок
- **THEN** connection/device context остаётся видимым рядом с рабочей областью без дублирования primary actions и без постоянной верхней шапки
- **AND** ширина text/file content ограничена для читаемости

#### Scenario: Narrow viewport или zoom 200 процентов

- **WHEN** viewport равен 360 пикселям либо browser zoom установлен на 200 процентов
- **THEN** области выстраиваются в одну колонку, длинные address, filename и URL не создают horizontal page scroll
- **AND** theme control, composer, progress и terminal actions остаются доступны с клавиатуры

#### Scenario: Верхняя часть страницы отображается после редизайна

- **WHEN** пользователь открывает страницу на любом поддерживаемом viewport
- **THEN** перед connection/device content отсутствует отдельная полоса с логотипом и меткой «Только локально»
- **AND** удаление шапки не удаляет page title, доступное имя продукта или локальную security guidance
