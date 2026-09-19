## Baseline

- Git baseline: `4f3d593e5ab650aadc2cf75c44a44dbf9b5f2431`.
- Web build: `vite build` успешно собирает локальные assets без внешних runtime-ресурсов.
- Web regression baseline: 10 выбранных Vitest-файлов, 91 тест, 91 успешно.
- Существующие visual snapshots: Chrome light/dark на 360, 768 и 1920 px, zoom 200 процентов, long text link, long filename и action states.

## Android layout inventory

| Экран | Текущая структура | Состояния и recovery | Командная граница |
| --- | --- | --- | --- |
| Home | status, endpoint/pairing, sessions, quick actions, active transfers | stopped, starting, running, stopping, permission/error, no-session, connected | действие пользователя передаёт один `HomeAction`; recomposition не запускает lifecycle-команду |
| Text | session choice, composer, feed, item actions | empty, draft, sending, delivered, failed, reconnecting | submit/retry передаёт один `TextAction`; draft change не отправляет текст |
| Files | picker/draft, metadata, queue, item actions | empty, selected, offered, connecting, transferring, verifying, completed, cancelled, failed | confirm/cancel/retry передаёт один `FileAction`; cancel picker сохраняет существующий draft |
| History | filters, loading/empty/content, details/delete actions | loading, empty, content, recoverable error | filter/retry/delete выполняются только из явного действия |
| Settings | port, limits, destination, trusted browsers | loading, draft, saving, saved, failed, destination unavailable | save/revoke/select выполняются только из явного действия; render не пишет настройки |

Top-level navigation использует bottom navigation до 600 dp и navigation rail от 600 dp. `DeviceBridgeTheme` выбирает light/dark через `isSystemInDarkTheme()`; ручного Android theme preference нет.

## Web semantic and layout inventory

- Перед `main` расположен отдельный `header.site-header` с brand badge и меткой «Только локально».
- `main` содержит доступный `h1#page-title`, connection column и workspace column в устойчивом DOM-порядке.
- Connection column содержит status live region и постоянно видимый `aside[data-role="security-warning"]`.
- Workspace содержит session/pairing, text и file sections; text/file sections скрыты до активной session.
- Pairing, text draft/feed и file draft/queue имеют собственные labels, error regions и polite announcers.
- На narrow viewport DOM-порядок остаётся connection → workspace; текущие CSS contracts запрещают фиксированные 360/768/1920 widths и horizontal overflow.

## Command-count invariants

| Сценарий | Baseline-инвариант |
| --- | --- |
| Render, theme/presentation update | 0 pairing, text или file network commands |
| Pairing submit | 1 submit для явной отправки формы; uncertain recovery проверяет исходный request без повторной отправки кода |
| Text draft edit | 0 send commands; draft остаётся browser-local в scope текущей session |
| Text submit/retry | 1 command на явный submit/retry; retry сохраняет прежний idempotency id |
| File selection/removal | 0 offer/upload commands до подтверждения |
| File confirm | 1 offer на подтверждённый batch; accepted items продолжают существующие transfer operations |
| Warning hide/restore | 0 session/security/network commands |

Инварианты закреплены существующими `sessionController`, `textTransferController`, `textTransferView`, `fileTransferController` и `fileTransferView` тестами. Редизайн не должен переносить отправку команд в render, theme listener или preference effect.

## Coverage required during redesign

- Android: Home, Text, Files, History, Settings; phone/large screen; light/dark; 200% font scale.
- Web: checking/offline/ready, pairing/confirming/connected/reconnecting, warning shown/hidden, text empty/sending/delivered/failed, file draft/active/verifying/completed/cancelled/failed.
- Accessibility: accessible product title, logical DOM/semantics order, visible focus, non-color status meaning, reduced motion and stable recovery focus.
