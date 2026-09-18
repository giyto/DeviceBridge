## ADDED Requirements

### Requirement: Web shell ограниченно восстанавливает event channel

Авторизованный web shell MUST выполнять bounded reconnect только для канала событий той же session и server generation. Во время reconnect UI MUST сохранять допустимые text и file drafts, блокировать действия, которым нужна подтверждённая session, и MUST NOT автоматически повторять pairing, text delivery, file offer, upload или download.

#### Scenario: Кратковременный разрыв восстановлен

- **WHEN** event channel временно закрывается и reconnect той же session завершается успешно в пределах установленного лимита
- **THEN** UI возвращается в connected state и получает актуальный snapshot
- **AND** ни одна пользовательская операция не создаётся и не отправляется повторно автоматически

#### Scenario: Лимит reconnect исчерпан

- **WHEN** все разрешённые reconnect attempts завершились без авторизованного соединения
- **THEN** web shell прекращает автоматические попытки и показывает действия проверить сервер, адрес и сеть либо начать pairing заново
- **AND** пользовательский draft остаётся доступным, пока вкладка не закрыта или пользователь его не очистил

#### Scenario: Session стала недействительной

- **WHEN** reconnect получает terminal session error или server generation изменился
- **THEN** web shell удаляет недействительный session token и возвращает пользователя к pairing
- **AND** trusted credential удаляется только когда сервер обозначил её истёкшей или отозванной

### Requirement: Web recovery сохраняет keyboard focus и доступный status

Web shell MUST иметь один краткий live status для важных connection и terminal operation transitions, видимый focus indicator и предсказуемое перемещение фокуса после dialog, retry и cancellation. Progress updates MUST NOT автоматически перемещать focus или объявляться чаще, чем требуется для понимания этапа.

#### Scenario: Pairing error исправляется с клавиатуры

- **WHEN** пользователь получает pairing error и активирует предложенное действие с клавиатуры
- **THEN** focus перемещается к следующему требуемому полю или действию
- **AND** введённое значение сохраняется либо очищается в соответствии с конкретной причиной ошибки

#### Scenario: Transfer отменён

- **WHEN** пользователь активирует cancellation из web UI
- **THEN** focus возвращается к устойчивому элементу соответствующего item или списка
- **AND** live status один раз сообщает об отмене без объявления каждого промежуточного progress update

#### Scenario: Recovery показан на узком viewport

- **WHEN** connection или operation error отображается при ширине 360 пикселей и browser zoom 200 процентов
- **THEN** причина, технические детали и primary action не перекрываются и не требуют горизонтальной прокрутки страницы
- **AND** secondary details можно открыть и закрыть с клавиатуры

### Requirement: Web shell разделяет визуальный язык DeviceBridge с Android

Web UI MUST использовать согласованные с Android semantic status colors, typography hierarchy, spacing rhythm, shape и action hierarchy, сохраняя browser conventions и semantic HTML. Тёмная тема MUST использовать спокойную near-black/graphite основу и layered surfaces, светлая тема — соответствующую читаемую палитру; обе темы MUST иметь видимые default, hover, pressed, focused, disabled, loading, error и success states без внешних fonts, CDN или styling runtime.

#### Scenario: Пользователь меняет системную тему

- **WHEN** browser color scheme меняется между light и dark
- **THEN** page применяет согласованные tokens без потери текста, border, focus indicator или status distinction
- **AND** change не требует перезагрузки внешнего ресурса

#### Scenario: Control получает keyboard focus

- **WHEN** пользователь перемещается по primary, secondary и destructive actions клавишей Tab
- **THEN** каждый focus state видим на соответствующем surface
- **AND** визуальный порядок и семантический порядок совпадают

### Requirement: Web shell адаптирует рабочую область без потери контекста

При достаточной ширине web shell SHALL показывать две согласованные области: connection/device context и text/files/activity workspace. На narrow viewport layout MUST переходить в одну колонку без горизонтального page overflow, сохраняя connection status и текущую operation. Text/link feed и file cards MUST использовать те же направления, stages, terminal statuses и применимые actions, что Android UI.

#### Scenario: Wide desktop viewport

- **WHEN** viewport достаточно широк для двух колонок
- **THEN** connection/device context остаётся видимым рядом с рабочей областью без дублирования primary actions
- **AND** ширина text/file content ограничена для читаемости

#### Scenario: Narrow viewport или zoom 200 процентов

- **WHEN** viewport равен 360 пикселям либо browser zoom установлен на 200 процентов
- **THEN** области выстраиваются в одну колонку, длинные address, filename и URL не создают horizontal page scroll
- **AND** composer, progress и terminal actions остаются доступны с клавиатуры

### Requirement: Motion объясняет состояние и уважает reduced motion

Web shell MAY использовать короткие ненавязчивые transitions для появления item, смены connection state и завершения progress, но MUST NOT задерживать действие или быть единственным признаком результата. При `prefers-reduced-motion: reduce` необязательная motion MUST быть отключена, а состояние MUST изменяться немедленно и понятно.

#### Scenario: Transfer завершается

- **WHEN** active file item становится completed или failed
- **THEN** UI может использовать краткий transition для смены stage и actions
- **AND** итог немедленно доступен текстом и live status

#### Scenario: Reduced motion включён

- **WHEN** browser сообщает `prefers-reduced-motion: reduce`
- **THEN** декоративные transitions и smooth scrolling отключаются
- **AND** focus, content order и terminal feedback сохраняются
