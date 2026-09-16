## Context

См. `proposal.md` для мотивации и границ. После `add-server-lifecycle` один process-wide coordinator управляет production Ktor/CIO runtime, LAN endpoint и foreground service. Production routing пока отдаёт только `/`, `/assets/*` и `/web-manifest.json`; unfinished `/api/v1/*` возвращают 404. Android UI получает lifecycle через domain use cases, а web shell собран Vite и работает полностью same-origin/offline.

Pairing пересекает Android UI, Ktor HTTP/WebSocket, web state, криптографические секреты и cleanup server lifecycle. Поэтому он остаётся внутри одного Gradle-модуля `app`, но вводит отдельную domain boundary для browser sessions; ViewModel не получает Ktor, service, `SecureRandom` или storage API.

## Goals / Non-Goals

**Goals:**

- построить единую process-wide state machine pairing/session, наблюдаемую Android UI и server adapters;
- сделать правильный код недостаточным без явного approve на телефоне;
- обеспечить token authorization HTTP и WebSocket без секретов в URL и журналах;
- гарантировать отзыв одной session и атомарное завершение всех sessions вместе с server generation;
- сохранить testability через fake clock, random source, token verifier и route fixture;
- оставить браузерный интерфейс same-origin, offline и совместимым с Chrome/Edge.

**Non-Goals:**

- persistent trusted-browser token, Android Keystore и экран управления доверенными браузерами;
- передача текста, ссылок или файлов и постоянная история;
- TLS, WebRTC, desktop companion, аккаунт, cloud relay и доступ через интернет;
- восстановление browser session после остановки сервера, process death или закрытия browser tab;
- автоматическое открытие страницы/сервера и скрытое подтверждение pairing.

## Decisions

### 1. Отдельная session boundary поверх существующего server lifecycle

В domain добавляются immutable модели `PairingCodeState`, `PendingBrowserRequest`, `BrowserSession`, `BrowserSessionError`, интерфейс `BrowserSessionRepository` и небольшие use cases наблюдения, approve, deny и revoke. Data-layer coordinator сериализует команды через `Mutex` и публикует один `StateFlow`; Android UI и notification только наблюдают его.

Каждый запуск runtime активирует новый opaque generation handle в session coordinator. Ktor routes захватывают этот handle, а любое обращение со старым handle отклоняется. `ServerRuntime.stop()` сначала закрывает generation и ожидающие requests/WebSockets, затем освобождает CIO listener. Это сохраняет единственный источник истины и не позволяет позднему callback прошлого запуска создать session в новом.

Альтернатива — хранить sessions в Ktor route objects и отдельно копировать их в ViewModel — отклонена из-за двух источников истины, сложного revoke и риска переживших stop callbacks.

### 2. Короткий код и подтверждение моделируются разными барьерами

При `activateGeneration` криптографический random source создаёт значение `000000..999999`; ведущие нули сохраняются. Срок считается по monotonic clock и ограничен пятью минутами. Timer автоматически заменяет истёкший код, а successful approve одноразово сжигает код и создаёт следующий. Код никогда не передаётся из Android state в Ktor/public manifest.

`POST /api/v1/session/challenge` создаёт bounded opaque `challengeId`. `POST /api/v1/session/confirm` принимает `challengeId`, code и нормализованный client label, валидирует их и suspend-ожидает решение Android host не более 60 секунд. Правильный code создаёт только `PendingBrowserRequest`; token возвращается тем же confirm response исключительно после approve. Deny, timeout, expiry и lifecycle stop завершают ожидающий response без token.

Альтернатива — выдать token сразу после code — отклонена, потому что нарушает обязательное подтверждение на телефоне. Отдельный polling endpoint не добавляется: он расширяет публичную поверхность и усложняет cleanup; bounded long-poll соответствует минимальному API ТЗ.

### 3. Rate limit привязан к source IPv4 и ограничен по памяти

Неверные подтверждения учитываются по нормализованному remote IPv4 внутри текущего generation. После пяти ошибок источник блокируется на 60 секунд; успешная проверка не переносит счётчик в другую generation. Registry имеет фиксированный предел записей и удаляет истёкшие oldest entries, чтобы запросы не создавали неограниченный рост памяти. Дополнительный небольшой global concurrency limit ограничивает число pending long-poll requests.

Альтернатива — один глобальный счётчик — позволила бы одному соседу заблокировать владельца. Только browser fingerprint отклонён: label и User-Agent контролируются клиентом и не являются идентичностью.

### 4. Raw token выдаётся один раз, server хранит только digest

После approve генератор создаёт 32 random bytes и кодирует их base64url без padding. Raw token возвращается только в JSON body успешного same-origin confirm response. Server хранит session metadata и SHA-256 digest token; проверка использует constant-time comparison. Token связывается с generation/session id и аннулируется при revoke или generation cleanup.

HTTP использует `Authorization: Bearer`; WebSocket принимает token только в первом JSON message `session.auth`, после чего raw token не повторяется. Auth frame должен прийти за короткий timeout до любых private events. Browser сохраняет raw token в `sessionStorage` текущей вкладки, не в URL, cookie или `localStorage`, и удаляет его при 401, revoke, stop либо несовместимой версии.

Альтернатива — cookie — требует отдельной CSRF-модели и не даёт преимущества для локального bearer API. Token в query string отклонён из-за browser history, access logs и referrer leakage. Полностью memory-only browser token отклонён как слишком неудобный при обычном refresh, хотя server-side срок всё равно ограничен lifecycle.

### 5. Host allowlist и same-origin guard применяются ко всему session API

Текущая allowlist фактического `host:port` сохраняется. Навигационные GET web assets допускают отсутствие `Origin`. Browser same-origin `GET /api/v1/status` также может не содержать `Origin` по правилам Fetch, поэтому для него обязательны разрешённый `Host` и действующий bearer token, а присутствующий `Origin` всегда проверяется на точное совпадение. API POST/DELETE и WebSocket upgrade требуют совпадающие `Host` и `Origin`; pairing POST дополнительно требует bounded `application/json`. Permissive CORS headers не добавляются. CSP остаётся `default-src 'self'; connect-src 'self'`, а web UI выводит client-provided значения только через text nodes.

Публичная поверхность: `/`, `/assets/*`, `/web-manifest.json`, `POST /api/v1/session/challenge`, `POST /api/v1/session/confirm`. Защищённая поверхность этого change: `GET /api/v1/status`, `DELETE /api/v1/session`, `WS /api/v1/events`. Unfinished text/file/transfer routes и `/diagnostics/*` сохраняют 404 в production независимо от token.

Альтернатива — разрешить API-запросы без `Origin` только по bearer token — отклонена: продуктом является встроенная browser page, поэтому более узкий browser-only contract предпочтительнее универсального LAN API.

### 6. Протокол имеет явные DTO и стабильные ошибки

Session HTTP DTO используют JSON, `protocolVersion`, bounded строки и opaque identifiers. Success confirm возвращает `sessionId`, `token`, server time и protocol version. Ошибки имеют стабильный machine-readable code и безопасное пользовательское message; при необходимости добавляются `retryAfterSeconds` и `attemptsRemaining`. Основные статусы: 400 invalid payload, 401 invalid/revoked token, 403 origin/decision denied, 409 stale/busy request, 410 expired challenge/code, 429 rate limited.

Каждое WebSocket message содержит `protocolVersion`, `messageId`, `type`, `timestamp`; unknown major version закрывает socket до регистрации session connection. Повторный `messageId` auth/control message не создаёт вторую операцию. Payload limits применяются до десериализации больших значений.

Альтернатива — свободные maps и текстовые ошибки — отклонена: следующий transfer change должен опираться на версионированную и тестируемую protocol boundary.

### 7. Android и web используют независимые presentation state machines

`HomeViewModel` объединяет server lifecycle и browser-session use cases в `UiState`: code/countdown, pending requests, active sessions и одноразовые approve/deny effects. Compose-компоненты получают только state/actions. Notification controller подписывается на фактический session count и не хранит собственный список.

Web controller имеет состояния `checking`, `readyForPairing`, `submitting`, `awaitingPhone`, `connected`, `blocked`, `expired`, `denied`, `sessionLost`, `offline`. DOM adapter остаётся отдельно тестируемым, все элементы доступны с клавиатуры, а transfer controls остаются disabled после подключения.

Альтернатива — встроить fetch/WebSocket логику непосредственно в DOM handlers или Ktor callbacks во ViewModel — отклонена как нарушение существующих Clean Architecture/MVVM границ.

### 8. Никакой persistent trust в текущем change

Android process хранит только текущие generation state и token digests в памяти. Session data не входит в `ServerSessionJournal`, Android backup, DataStore или Room. Browser `sessionStorage` используется только для raw session token текущей вкладки. После server restart всегда требуется новый код и approve.

Trusted browser потребует отдельного отзывного credential, encrypted Android storage, сроков хранения и UI настроек; это остаётся этапом 8 roadmap. Такое разделение не позволяет временно сохранить session token и позднее ошибочно назвать его trusted credential.

## Risks / Trade-offs

- [Локальный HTTP/WebSocket не шифрует code и token от активного наблюдателя доверенной сети] → сохраняем видимое предупреждение, короткий code, phone approval, session-only token и не заявляем защиту уровня TLS; отдельный security spike остаётся перед release.
- [Шестизначный code имеет небольшое пространство] → TTL пять минут, пять попыток, per-IP block, global concurrency bound и обязательный approve делают code только первым фактором.
- [Long-poll confirm удерживает server resources] → timeout 60 секунд, ограничение pending requests, маленький JSON payload и гарантированное завершение при stop/revoke.
- [Client label и IP могут ввести пользователя в заблуждение] → UI помечает их как информацию о локальном запросе, не как проверенную личность; approve всегда явный.
- [XSS смог бы прочитать sessionStorage] → bundled-only assets, strict CSP, same-origin guard, запрет HTML insertion и web tests для безопасного rendering; token не сохраняется дольше вкладки/server generation.
- [Late approve/revoke/WebSocket callbacks могут пересечь restart] → все команды содержат generation/request/session ids, state изменяется под mutex, stale callbacks отклоняются.
- [Wall clock может измениться] → TTL и блокировки считаются monotonic clock; wall time используется только для отображаемых protocol timestamps.
- [Несколько вкладок с одного IPv4 делят rate limit] → это осознанный безопасный предел домашнего сценария; корректные requests могут существовать параллельно и различаются opaque request id.

## Migration Plan

1. Добавить domain contracts и полностью протестированный session coordinator без подключения production routes.
2. Интегрировать generation activation/cleanup с existing runtime и foreground notification, сохранив прежние lifecycle tests.
3. Подключить session JSON routes, authorization guard и WebSocket; сначала оставить web UI за feature composition boundary.
4. Обновить Android UI и затем web shell, после чего собрать assets в APK.
5. Выполнить unit, route integration, web, Compose и connected tests API 29/API 37.1, затем ручной pairing Chrome/Edge по Wi-Fi и hotspot.

Rollback выполняется удалением session route composition и возвратом прежнего статического web shell; server lifecycle и публичный manifest не требуют миграции данных. Persistent schema отсутствует.
