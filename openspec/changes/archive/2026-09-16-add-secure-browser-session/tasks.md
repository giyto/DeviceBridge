## 1. Зафиксировать baseline и session protocol

- [x] 1.1 Создать `docs/verification/secure-browser-session.md`, записать текущие команды и результаты web/unit/lint/assemble/connected tests, размеры и SHA-256 debug/release APK; подтвердить воспроизводимый baseline до функциональных изменений.
- [x] 1.2 Сначала добавить contract tests публичной route allowlist, затем зафиксировать точную поверхность этапа: public root/assets/manifest/challenge/confirm, protected status/session/events и 404 для diagnostics/transfer; проверить тесты debug и release composition.
- [x] 1.3 Сначала написать serialization tests, затем определить versioned session HTTP DTO и стабильную error envelope с лимитами полей; проверить round-trip, unknown major version, лишние/пропущенные поля и oversized payload.
- [x] 1.4 Сначала написать protocol tests, затем определить WebSocket envelope с `protocolVersion`, `messageId`, `type` и `timestamp` и auth payload; проверить reject неизвестной major version и повторного control `messageId`.
- [x] 1.5 Провести dependency/architecture baseline: подтвердить имеющиеся Ktor serialization/WebSocket возможности либо добавить минимальные production dependencies; проверить Gradle sync, debug/release compile и отсутствие duplicate classes.

## 2. Добавить domain-контракты browser session

- [x] 2.1 Сначала написать unit-тесты invariants, затем добавить immutable `PairingCodeState`, `PendingBrowserRequest`, `BrowserSession`, `BrowserSessionState` и типизированные ошибки без импортов Android/Ktor/Compose; проверить domain boundary test.
- [x] 2.2 Сначала написать reducer tests всех pairing/session переходов, затем реализовать чистый reducer для inactive, ready, pending, connected, blocked и error данных; проверить запрет stale и недопустимых переходов
- [x] 2.3 Сначала написать contract tests, затем определить `BrowserSessionRepository` для наблюдения state, approve, deny и revoke; проверить, что интерфейс не раскрывает raw token Android UI.
- [x] 2.4 Сначала написать delegation tests, затем добавить use cases наблюдения, approve/deny pending request и revoke active session; проверить, что каждый use case зависит только от domain repository.
- [x] 2.5 Добавить generation/request/session identifier value objects и тесты равенства/валидации; проверить невозможность смешать идентификаторы разных типов и пустые значения.
- [x] 2.6 Расширить архитектурный тест: HomeViewModel не импортирует Ktor, service, random/storage API, а domain не импортирует Android/Ktor; проверить тест на полном production source set.

## 3. Реализовать криптографию, TTL и ограничения попыток

- [x] 3.1 Сначала написать deterministic fake-random tests, затем реализовать crypto random source для шестизначного code с ведущими нулями и 32-byte base64url token; проверить формат, диапазон и отсутствие modulo bias API.
- [x] 3.2 Сначала написать token verification tests, затем хранить только SHA-256 digest и сравнивать digest constant-time; проверить accept правильного token и reject изменённого, пустого и token другой generation.
- [x] 3.3 Сначала написать fake monotonic-clock tests, затем реализовать пяти минутный code TTL, 60-секундный pending timeout и автоматическую rotation; проверить устойчивость к изменению wall clock.
- [x] 3.4 Сначала написать boundary tests пяти неверных попыток, затем реализовать per-source-IPv4 rate limiter с блокировкой не менее 60 секунд; проверить remaining attempts, retry-after и независимость разных IPv4.
- [x] 3.5 Сначала написать memory-bound tests, затем ограничить число rate-limit records, challenges и concurrent pending requests с удалением истёкших записей; проверить отсутствие неограниченного роста на серии hostile inputs.
- [x] 3.6 Добавить тесты нормализации browser label, source IPv4 и входных размеров; проверить отклонение control characters/oversized JSON и безопасное текстовое отображение разрешённых значений.

## 4. Построить process-wide session coordinator

- [x] 4.1 Сначала написать activation tests, затем реализовать coordinator с `Mutex`, `StateFlow` и opaque generation handle; проверить один активный generation и новый code при каждом activate.
- [x] 4.2 Сначала написать challenge tests, затем реализовать создание, срок жизни и bounded registry opaque challenge identifiers; проверить отсутствие code/token в challenge response.
- [x] 4.3 Сначала написать wrong/expired/blocked tests, затем связать confirm validation с code TTL и rate limiter; проверить, что такие запросы не создают pending request или session.
- [x] 4.4 Сначала написать approve/deny/timeout tests, затем реализовать bounded suspend confirm через pending request и `CompletableDeferred`; проверить выдачу token только после approve и завершение без token во всех остальных исходах.
- [x] 4.5 Сначала написать concurrent request tests, затем обеспечить привязку решения к request/generation и идемпотентность повторного approve/deny; проверить, что один request создаёт не более одной session.
- [x] 4.6 Сначала написать session registry tests, затем реализовать lookup bearer token, список metadata и индивидуальный revoke; проверить немедленный reject token и закрытие связанного WebSocket после revoke.
- [x] 4.7 Сначала написать cleanup/stale-callback tests, затем реализовать `closeGeneration`, который аннулирует code, challenges, pending requests, sessions, tokens, timers и sockets; проверить отсутствие state/callback прошлого generation после нового activate.

## 5. Подключить защищённые Ktor HTTP и WebSocket routes

- [x] 5.1 Выделить общий request security guard для фактического Host, exact same-origin API/upgrade, JSON content type и body limits; проверить 403/400 для чужого Origin/Host, отсутствующего Origin API и oversized body без permissive CORS.
- [x] 5.2 Сначала написать route tests `POST /api/v1/session/challenge`, затем подключить challenge coordinator и versioned response; проверить success, invalid version, invalid metadata и capacity error.
- [x] 5.3 Сначала написать route tests `POST /api/v1/session/confirm`, затем реализовать long-poll response approve/deny/timeout/expired/blocked; проверить стабильные HTTP statuses/error codes и отсутствие token в неуспешных ответах.
- [x] 5.4 Сначала написать bearer authorization tests, затем реализовать parsing одного `Authorization: Bearer` и digest lookup; проверить 401 для missing, malformed, duplicate, unknown, revoked и old-generation token.
- [x] 5.5 Сначала написать protected status tests, затем реализовать `GET /api/v1/status` с минимальными session/server данными; проверить отсутствие pairing codes, чужих tokens и diagnostic data.
- [x] 5.6 Сначала написать own-session close tests, затем реализовать защищённый `DELETE /api/v1/session`; проверить отзыв только предъявившей token session и сохранение остальных.
- [x] 5.7 Сначала написать WebSocket integration tests, затем подключить `/api/v1/events` с auth-first timeout, version/message validation и session binding; проверить отсутствие events до auth, close при bad/revoked token и reject duplicate auth.
- [x] 5.8 Повторить negative release route tests: `/diagnostics/*` и unfinished text/file/transfer routes возвращают 404 даже с действующим token; проверить отсутствие diagnostic token и trusted-browser API в release APK/graph.

## 6. Связать sessions с server lifecycle и notification

- [x] 6.1 Сначала написать runtime integration test, затем активировать session generation только для успешно запущенного listener и передать handle Ktor routes; проверить cleanup при bind/start failure без опубликованного code.
- [x] 6.2 Сначала написать ordered-stop test, затем закрывать session generation до CIO listener/notification cleanup; проверить завершение pending confirm и WebSockets без зависания stop timeout.
- [x] 6.3 Расширить network/permission tests: loss, IPv4 change и revoke permission аннулируют все sessions, а новый явный start требует новый pairing; проверить old token на новом endpoint возвращает 401.
- [x] 6.4 Сначала написать notification model tests, затем получать фактическое число active sessions из process-wide state; проверить 0 до pairing, увеличение/уменьшение при approve/revoke и отсутствие code/token в notification.
- [x] 6.5 Расширить process-kill/recreation tests: новый process не восстанавливает code/session/token из `ServerSessionJournal`, а Activity recreation видит тот же живой generation; проверить API 29 и API 37.1.

## 7. Реализовать Android pairing UI

- [x] 7.1 Сначала написать HomeViewModel tests объединённого lifecycle/session state, затем подключить session use cases через Hilt; проверить code/countdown, pending, connected count и одноразовые approve/deny/revoke actions без прямого service control.
- [x] 7.2 Сначала добавить Compose tests Running без client, затем показать pairing code, countdown и инструкцию только при активном generation; проверить отсутствие code в Stopped/Starting/Stopping/Error и после stop.
- [x] 7.3 Сначала добавить Compose tests pending request, затем реализовать карточки browser label/source IPv4 с доступными «Разрешить»/«Отклонить»; проверить несколько requests, точное action id, timeout и защиту от двойного нажатия.
- [x] 7.4 Сначала добавить Compose tests active sessions, затем показать список/число browsers и действие индивидуального отключения; проверить немедленное удаление после revoke и отсутствие raw token в semantics/UI.
- [x] 7.5 Обновить disabled transfer UX и провести Compose matrix светлой/тёмной темы, font scale и API 29/37.1: до pairing предлагается подключение, после pairing сообщается следующий этап; проверить доступность и отсутствие обрезания основных действий.

## 8. Реализовать pairing в bundled web shell

- [x] 8.1 Сначала написать Vitest tests session API client, затем реализовать same-origin challenge/confirm/status/delete calls, typed errors и bearer header; проверить, что token никогда не добавляется в URL/log output.
- [x] 8.2 Сначала написать controller state tests, затем реализовать checking/ready/submitting/awaiting/connected/blocked/expired/denied/sessionLost/offline transitions; проверить retry, conflict blocking и очистку token на 401.
- [x] 8.3 Сначала написать storage tests, затем сохранять token только в `sessionStorage` текущей tab и удалять при revoke/stop/incompatible version; проверить отсутствие записи в localStorage/cookie и восстановление при refresh той же вкладки.
- [x] 8.4 Сначала написать DOM/accessibility tests, затем добавить шестизначную форму, waiting/connected/error states и disabled transfer controls; проверить keyboard flow, visible focus, live status и безопасный text-only rendering client data.
- [x] 8.5 Сначала написать WebSocket client tests, затем реализовать same-origin `/api/v1/events`, первый `session.auth`, bounded reconnect и возврат к pairing при auth close; проверить отсутствие token в URL и events до подтверждённого auth.
- [x] 8.6 Выполнить `npm.cmd test`, typecheck и два последовательных production build; проверить deterministic hashed assets, отсутствие external URLs/service worker и успешную упаковку актуальной страницы в debug/release APK.

## 9. Провести системную приёмку change

- [x] 9.1 Выполнить server integration matrix: correct/wrong/expired code, five-attempt block, approve, deny, timeout, concurrent requests, duplicate decision, HTTP/WS without token, revoke и old generation; записать точные результаты в verification report.
- [x] 9.2 Выполнить web tests/build и Gradle unit tests, lint, assembleDebug/Release; проверить ноль failures/errors, проанализировать warnings и обновить размеры/SHA-256 APK в verification report.
- [x] 9.3 На API 29 выполнить connected tests и ручной pairing через ADB forward: approve/deny/revoke, Activity background/recreation, notification count и stop; подтвердить закрытие WebSocket и 401 старого token.
- [x] 9.4 На API 37.1 выполнить connected tests и permission matrix: LAN/notification grant-deny, runtime LAN revoke с active session, process kill и explicit restart; подтвердить отсутствие auto-restore session и crash.
- [x] 9.5 На физическом телефоне открыть Wi-Fi endpoint в Chrome и Edge, пройти code + phone approve, обновить tab, проверить protected status, Android session list, индивидуальный revoke и повторный pairing без `adb forward`.
- [x] 9.6 Повторить основной pairing/revoke/stop сценарий через hotspot без интернета; проверить новый IPv4, invalidation старого token, отсутствие внешних запросов и корректные состояния обоих интерфейсов.
- [x] 9.7 Выполнить security review: убедиться, что code/raw tokens отсутствуют в URL, manifest/assets, Logcat, notification, persistent Android files, backup config, test reports и Git diff; зафиксировать осознанное ограничение локального HTTP.
- [x] 9.8 Сверить реализацию с ТЗ 2.0, roadmap, proposal, design и четырьмя delta specs; подтвердить одно приложение, Clean Architecture/MVVM/SOLID и отсутствие trusted browser/transfer scope.
- [ ] 9.9 Выполнить `openspec validate add-secure-browser-session --strict`, передать пользователю пошаговый ручной чек-лист и после подтверждения синхронизировать main specs, архивировать change, создать локальный commit и push в GitHub.
