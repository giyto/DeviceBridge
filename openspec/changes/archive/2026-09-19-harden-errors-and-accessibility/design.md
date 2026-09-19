## Context

DeviceBridge уже разделён на domain contracts и reducers, data coordinators/repositories, Compose feature screens и встроенный TypeScript web shell. Server lifecycle, pairing/session, text/file transfer, Room history, DataStore settings и SAF destination имеют собственные состояния и ошибки, но сейчас эти результаты преобразуются для UI независимо. Из-за этого одинаковая причина может иметь разные тексты и recovery actions, а connection reconnect рискует смешаться с повтором пользовательской операции.

Изменение затрагивает Android domain/data/presentation, Ktor protocol responses и web controllers/views. Оно должно сохранить текущую Clean Architecture, однонаправленный data flow, один Android APK, локальный HTTP/WebSocket и существующие idempotency identifiers. См. мотивацию в `proposal.md` и наблюдаемые контракты в `specs/**/spec.md`.

## Goals / Non-Goals

**Goals:**

- Ввести один набор стабильных failure codes, recovery semantics и privacy rules, который можно отобразить и протестировать на Android и в web без связывания domain с локализованными строками.
- Разделить persistent screen state и одноразовые UI effects, чтобы recreation, reconnect и retry не дублировали операции.
- Сделать bounded reconnect только transport-механизмом, сохранив manual idempotent retry для text/file operations.
- Довести Compose и web UI до проверяемого accessibility/responsive baseline на целевой матрице.
- Сформировать единый визуальный язык DeviceBridge и переработать основные operational surfaces без изменения продуктовой архитектуры.
- Добавить изменения инкрементально поверх текущих reducers, repositories и controllers без новой инфраструктуры и без миграции пользовательской базы данных.

**Non-Goals:**

- Выбор HTTPS/mTLS/другого защищённого transport, изменение доверительной модели локального HTTP или добавление cloud relay.
- Desktop companion, PWA, фоновый сервер после reboot, автоматическое чтение clipboard или автоматический transfer retry.
- Release signing, R8/ProGuard, финальная performance/security-аудитория и публикация сборки — это этап 10.
- Полная замена существующих feature reducers и controllers универсальным state-machine framework.
- Создание отдельного brand book, новых иллюстраций, 3D-графики или новой функциональности вроде QR pairing под видом редизайна.

## Decisions

### 1. Domain failure taxonomy отделена от UI-текстов и protocol DTO

В `domain/error` будет введён закрытый набор `FailureCode`, `FailureSeverity`, `RecoveryAction` и безопасный `FailureContext`. Feature-specific ошибки (`ServerLifecycleError`, session/text/file/persistence results) сохраняются как источники истины и преобразуются в общий `UserFacingFailure` на границе presentation. Общий объект содержит стабильный код, recoverable/terminal признак, разрешённые действия и только санитизированные параметры; локализованные строки выбираются Android/web presentation по коду.

Ktor errors используют wire-level `errorCode` из того же стабильного каталога, но не сериализуют Kotlin domain object напрямую. Web сопоставляет wire code со своей исчерпывающей таблицей текста и actions; неизвестный code становится безопасной `unknown_error` с предложением обновить страницу или начать новую операцию.

Почему: domain остаётся независимым от Android Resources и DOM, а протокол не становится случайным отражением внутренних классов. Альтернатива — хранить готовый русский текст в exceptions/HTTP responses — отвергнута из-за расхождения платформ, утечки деталей и невозможности надёжно тестировать recovery.

### 2. Privacy filter применяется до логирования и presentation

Все технические сведения проходят через allowlist полей: protocol version, безопасный operation id, direction, size category, lifecycle state и стабильный failure code. Pairing code, bearer/session/trusted tokens, payload, remote filename до sanitization, полный URI/path и stack trace не попадают в `UserFacingFailure`, analytics отсутствует, а debug logs маскируют credential-like значения.

Почему: скрытие деталей только в UI оставило бы утечку в logs или protocol DTO. Альтернатива — blacklist строк — отвергнута как неполная и хрупкая.

### 3. Persistent state и одноразовые effects моделируются отдельно

Каждый Android feature ViewModel хранит immutable `UiState`: initial loading, content/empty, drafts, operation statuses и текущий failure. Открытие Settings, SAF picker, системного permission dialog, snackbar announcement и focus request являются `UiEffect` с уникальным id и подтверждением consumption. Recreation повторно подписывается на repositories и восстанавливает safe draft через `SavedStateHandle`, но не повторяет effects и commands.

Web controllers аналогично владеют serializable view state, а DOM view только render-ит состояние и исполняет явные effects. Drafts сохраняются в памяти вкладки; session/trusted credential stores не используются как хранилище payload.

Почему: это предотвращает повторный start/retry/picker после recomposition или re-render. Альтернатива — запуск side effects из composable/render — отвергнута из-за duplicate operations.

### 4. Reconnect и operation retry — две независимые state machines

`sessionEventSocketClient` и connection controller получают bounded exponential schedule с небольшим jitter и generation guard. Reconnect разрешён только для прежней session; terminal authorization error, server generation mismatch или исчерпание attempts переводят connection в `needs_user_action`. Успешный reconnect сначала запрашивает authoritative snapshot, затем обновляет UI.

Text/file coordinators никогда не слушают событие reconnect как команду повторной отправки. Retry доступен только через user intent и проходит feature validator. Для той же неизменённой операции сохраняется `messageId`/`transferId`; изменённый payload создаёт новый id. Acknowledgement и server-side idempotency registries остаются авторитетными для предотвращения duplicate delivery.

Почему: автоматический replay после неопределённого результата может продублировать текст, файл или запись history. Альтернатива — auto retry всего запроса — отвергнута как небезопасная; альтернатива без reconnect ухудшает кратковременные разрывы и не нужна.

### 5. Lifecycle recovery создаёт новый generation только по user intent

Ошибки permission, endpoint resolution, bind/start и network/IP transition преобразуются в разные failure codes. При потере сети или смене IP coordinator завершает старый runtime, sessions и operations, очищает опубликованный endpoint и оставляет lifecycle в Stopped/Failed. Только `StartServerUseCase`, вызванный явным действием пользователя, создаёт новый generation и новый pairing code.

Почему: это сохраняет существующий принцип explicit server lifecycle и исключает stale адрес/session. Альтернатива — автоматический restart на новой сети — отвергнута как неожиданное расширение поверхности доступа.

### 6. File cleanup и queue release выполняются в одном terminal transition

`FileTransferCoordinator` и reducer получают единый terminalization path, который атомарно фиксирует completed/cancelled/failed, закрывает stream/descriptor, освобождает Wi-Fi lock и scheduler slot, а затем запускает следующий независимый item. Partial output удаляется через `PartialDocumentManager`; если provider не позволяет удалить документ, item остаётся failed и output не публикуется как completed.

Retry создаёт новую execution attempt внутри того же transfer identity после повторной проверки source metadata, destination access и free-space signals. Отмена SAF picker не terminalizes offer автоматически: состояние остаётся `awaiting_destination`, пока пользователь явно не отменит offer или session/source не истекут.

Почему: queue и ресурсы не должны зависеть от того, успел ли UI отобразить 100%. Альтернатива — создавать новый transfer item при каждом retry — отвергнута из-за дубликатов и неоднозначной history.

### 7. Persistence errors не превращаются в defaults

History/Settings repositories возвращают явные loading/success/failure results. `empty` создаётся только из успешного пустого query. Settings reducer держит persisted value и editable draft отдельно; failed save не меняет active value. SAF tree URI считается active только после проверки persistable grant и пробного доступа; revocation переводит destination в unavailable, не очищая history/trust/settings.

Почему: default/empty fallback скрывает повреждение или недоступность storage. Альтернатива — всегда продолжать с defaults — оставлена только для первого успешного чтения отсутствующих настроек, как уже определено основной спецификацией.

### 8. Accessibility реализуется как часть reusable UI contracts

Compose получает общие `StateSurface`, `FailureCard`, `RecoveryActionButton` и status semantics. Интерактивные элементы используют нативные roles и текстовые labels; декоративные изображения исключаются из semantics. Live region применяется только для connection/pairing/terminal transition, а progress анонсируется по смене этапа, не по каждому проценту. Layout использует доступную ширину и перенос, а не фиксированные размеры; проверяется font scale 200% на phone и large screens.

Web shell использует semantic HTML, landmarks, `aria-live` status/alert regions, нативные button/input/details и управляемое восстановление focus после dialog/cancel/retry. CSS остаётся mobile-first и проверяется при 360/768/1920 px, zoom 200%, light/dark и reduced motion. Новая accessibility-библиотека в runtime не требуется.

Почему: единичные labels не обеспечивают предсказуемый flow. Альтернатива — отдельный accessibility-only экран — отвергнута; доступность должна быть свойством основных сценариев.

### 9. Visual system общая по смыслу, но нативная по реализации

Вводится небольшой набор semantic design tokens: background/surface layers, primary/secondary text, accent, success/warning/error, borders/focus, typography roles, spacing 4/8, shapes и elevation. Android реализует tokens через Material 3 theme и reusable Compose components; web — через CSS custom properties и semantic component classes. Названия semantic tokens и смысл состояний совпадают, но Android и web не разделяют generated code и не имитируют controls другой платформы.

Android Home строится как connection dashboard: сверху status surface, затем address/pairing block, connected sessions, quick actions и active transfers. После подключения подробная инструкция collapses, но остаётся доступной. Top-level destinations используют bottom navigation на phone и navigation rail на large screen. Text использует session feed и доступный composer; Files — одну operational card на item с предсказуемым набором actions.

Web shell использует две колонки на wide viewport: connection/device context и operational workspace. При narrow viewport или zoom 200% области переходят в одну колонку без смены DOM reading order. Text/link items и file cards используют те же direction/stage/status semantics, что Android. Длинные URL, addresses и filenames обрабатываются wrap/ellipsis вместе с доступным полным значением, а action row переносится внутри card.

Motion ограничивается короткими transitions смены состояния, появления item и terminal progress. State меняется до или одновременно с animation; `prefers-reduced-motion` и Android animator scale отключают необязательную motion. Скорость и ETA показываются только при стабильном расчёте, иначе используется stage и indeterminate progress.

Почему: общий semantic system делает продукт целостным, но единый набор pixel-компонентов ухудшил бы platform conventions и сопровождение. Альтернатива — только косметически поменять цвета существующих экранов — отвергнута, потому что не исправляет иерархию, overflow и action placement.

### 10. Проверки строятся вокруг mapping tables, state transitions и visual states

JVM tests покрывают полный mapping feature error → failure code/action/privacy, reducers, generation guards, idempotent retry и file terminalization. Ktor tests проверяют status/error DTO без секретов. Compose tests проверяют semantics, focusable actions, state rendering, theme tokens, font-scale/adaptive navigation и отсутствие clipped actions. Vitest покрывает web reducers/controllers, bounded reconnect, drafts и semantic view states; Playwright — keyboard/focus/live regions, long-content overflow, narrow/wide layout, light/dark/reduced-motion и Chrome/Edge. Ручная матрица дополняет автоматизацию TalkBack, API 29/API 37.1 и Windows 10/11.

Почему: snapshot-only тесты не доказывают recovery или отсутствие duplicate side effects. Альтернатива — только ручное тестирование — отвергнута как нерепродуцируемая.

## Risks / Trade-offs

- [Слишком общий failure catalog потеряет полезную причину] → Для каждого источника ошибки составить исчерпывающий mapping test и разрешать `unknown_error` только как forward-compatible fallback.
- [Разные Android и web тексты начнут расходиться] → Проверять одинаковый failure code, severity и recovery action контрактом; wording может отличаться только под формат платформы.
- [Bounded reconnect создаст гонку со stop/revoke] → Все callbacks маркировать connection attempt и server generation, игнорировать stale completion и отменять timers при terminal state.
- [Сохранённый draft удержит чувствительный payload дольше ожидаемого] → Хранить drafts только в памяти текущего generation/tab или в `SavedStateHandle`, не в Room/DataStore/trusted credential storage; очищать при explicit discard, terminal success и завершении lifecycle.
- [SAF provider не позволит удалить partial output] → Не помечать transfer completed, показать безопасную инструкцию и не открывать partial document автоматически.
- [Accessibility announcements станут шумными] → Анонсировать только этапы и terminal transitions, throttle progress rendering и проверять TalkBack/screen reader вручную.
- [Расширение error DTO нарушит старые web assets] → Добавлять стабильные поля обратно совместимо, сохранять безопасный generic message и собирать web assets вместе с APK.
- [Визуальная переработка расширит change и вызовет функциональные регрессии] → Сначала зафиксировать tokens/reusable surfaces, переносить по одному screen, сохранять reducers/controllers и проверять каждый экран state/interaction tests до следующего.
- [Android и web станут пиксельно похожими ценой platform conventions] → Согласовать semantic tokens и status/action meanings, но использовать Material 3 и semantic HTML/CSS нативно для каждой платформы.

## Migration Plan

1. Добавить общий failure catalog, privacy filter и mapping tests без переключения UI.
2. Перевести lifecycle/session, затем text/file и persistence на типизированные results, сохраняя существующие protocol success paths.
3. Ввести semantic design tokens и reusable state surfaces без смены feature logic, затем поочерёдно перевести Android Home/Text/Files/History/Settings и web shell.
4. Перевести Android UiState/UiEffect и adaptive navigation, затем web controllers/views, wide/narrow layout и reconnect policy.
5. Включить новые Ktor error codes и одновременно пересобрать embedded web assets; отдельная server/client rollout не требуется, поскольку они поставляются в одном APK.
6. Выполнить automated и manual acceptance matrix. При регрессии откатить change одним Git revert: schema/Room migration и постоянное преобразование пользовательских данных отсутствуют.
