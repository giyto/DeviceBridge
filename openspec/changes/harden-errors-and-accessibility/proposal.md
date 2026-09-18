## Why

Основные сценарии DeviceBridge уже реализованы, но сообщения и действия восстановления формировались поэтапно и пока не образуют единую, проверяемую модель пользовательских ошибок и доступности. Перед security/release-этапом необходимо сделать разрывы соединения, отказы разрешений, ошибки передачи и состояния экранов предсказуемыми на Android, в Chrome и Edge без автоматических опасных повторов.

## What Changes

- Вводится единая пользовательская классификация recoverable и terminal ошибок с безопасными техническими деталями, стабильными кодами и конкретным следующим действием.
- Android и web получают согласованные состояния loading, empty, disabled, success, cancellation, offline и error без ложного успеха и без потери уже введённых или выбранных данных там, где повтор допустим.
- Ограниченное восстановление соединения отделяется от повторного выполнения пользовательской операции: WebSocket может переподключаться в заданных пределах, а повтор text/file transfer после неопределённого результата запускается только пользователем и сохраняет идемпотентный идентификатор.
- Добавляются понятные инструкции первого запуска и подключения: одна локальная сеть, актуальный адрес, подтверждение на телефоне, доверенная сеть и действия при смене IP или недоступности сервера.
- Android UI и web shell укрепляются для TalkBack/screen reader, клавиатуры, видимого фокуса, live status, увеличенного шрифта, светлой/тёмной темы, phone/large-screen layout и browser viewport 360–1920 px.
- Android и web получают единый спокойный визуальный язык DeviceBridge: design tokens, согласованные surfaces, типографику, status colors, primary/secondary action hierarchy и все обязательные interaction states.
- Android Home становится компактным dashboard со status surface, блоком подключения, sessions, quick actions и active transfers; Text и Files получают устойчивые ленты/карточки, а phone bottom navigation адаптируется в navigation rail на large screen.
- Web shell получает двухколоночный desktop layout, одноколоночный narrow layout и согласованные text/file/status surfaces без overflow при zoom 200% и длинном содержимом.
- Добавляется сдержанная motion для смены состояния и прогресса с обязательной поддержкой reduced motion; внешние UI-ресурсы и styling dependencies не требуются.
- Добавляется сквозная acceptance-матрица API 29/API 37.1 и Chrome/Edge на Windows 10/11 для ошибок, recovery, cancellation и accessibility.
- Change не выбирает новый защищённый transport, не добавляет desktop companion, внешний backend, скрытый automatic retry или release signing/R8; это остаётся отдельным этапом 10.

## Capabilities

### New Capabilities

- `error-recovery-and-accessibility`: общая observable-модель ошибок, безопасных деталей, пользовательского recovery и accessibility acceptance для Android и browser UI.

### Modified Capabilities

- `android-app-shell`: onboarding, dashboard Home, адаптивная навигация, единая visual system, согласованные screen states, доступные recovery actions и phone/large-screen layouts.
- `browser-web-interface`: единая visual system с Android, двухколоночный/одноколоночный layout, ограниченный reconnect, сохранение допустимого draft и keyboard/screen-reader/responsive поведение.
- `server-lifecycle`: точные recoverable причины остановки/запуска и действия пользователя без stale endpoint или автоматического переноса session.
- `secure-browser-session`: различимые pairing/session/trusted-reconnect ошибки и безопасный возврат к корректному шагу авторизации.
- `text-and-link-transfer`: определённые terminal/retry состояния, устойчивая session feed и link actions без overflow, без duplicate delivery и с сохранением пользовательского draft.
- `file-transfer`: единая семантика queue/cancel/failure/manual retry, доступные file cards и progress surfaces, безопасная судьба partial output и выбранного draft.
- `local-history-and-settings`: честные loading/empty/error/retry состояния при недоступности persistence, SAF destination и сохранения настроек.

## Impact

- Android presentation/domain: общие error/recovery модели, screen state reducers, reusable design tokens/components, onboarding/connection dashboard, adaptive navigation, semantics и Compose layouts.
- Embedded server/session/transfer: стабильное отображение существующих protocol/lifecycle ошибок, идемпотентный manual retry и bounded reconnect без расширения LAN API.
- Web: общие design tokens/surfaces, wide/narrow shell layouts, session/event recovery, сохранение допустимых drafts, accessible announcements/focus и responsive UI.
- Testing/docs: JVM, Ktor, Compose, Vitest и Playwright scenarios, accessibility checks, API 29/API 37.1 и Chrome/Edge manual matrix.
- Инфраструктура остаётся прежней: один Android APK, локальный HTTP/WebSocket, без аккаунтов, облака и аренды сервера.
