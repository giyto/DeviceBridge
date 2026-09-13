# Design: Android embedded Ktor server viability spike

## Context

DeviceBridge 2.0 должен поставляться одним Android APK. Телефон является локальным сервером, а компьютер использует браузер как клиент. Это решение устраняет отдельное Windows-приложение, но переносит главный технический риск на Android runtime: сервер должен стабильно слушать порт, обслуживать WebSocket и передавать 500 МБ на API 29 и API 37.1.

Ktor 3.5.2 является актуальным patch-релизом согласованной ветки 3.5.x. Документация Ktor перечисляет CIO для JVM и Native, но не выделяет стандартное Android/JVM-приложение как отдельную гарантированную server-engine платформу. Поэтому совместимость нельзя считать доказанной только успешной компиляцией — требуется реальный instrumented spike.

Android 17/API 37 добавляет runtime-разрешение ACCESS_LOCAL_NETWORK для приложений с targetSdk 37. Spike обязан учитывать его, хотя полноценный production UX разрешения относится к следующему change жизненного цикла.

## Goals

- Доказать или опровергнуть пригодность Ktor 3.5.2 + CIO внутри Android APK.
- Проверить одинаковый набор сценариев на API 29 и API 37.1.
- Проверить HTTP, WebSocket, повторный lifecycle, 500 МБ streaming, отмену и восстановление.
- Получить воспроизводимые метрики памяти и изменения APK.
- Сохранить технический spike за архитектурным seam, чтобы отказ от CIO/Ktor не менял domain и будущий протокол.
- Не допустить диагностические точки входа в release-сборку.

## Non-Goals

- Production-маршруты /api/v1 и финальная схема сообщений.
- Постоянный foreground service и восстановление после убийства процесса.
- Pairing, доверенные браузеры, пользовательская авторизация и TLS.
- Веб-интерфейс, история, Room и передача пользовательских файлов.
- Внедрение Hilt до принятия server adapter.
- Поддержка внешней сети или облачного backend.

## Decisions

### 1. Первый кандидат — Ktor 3.5.2 с CIO

Spike использует фиксированную версию Ktor 3.5.2 и один engine CIO. Наличие нескольких engines в одном spike усложнило бы диагноз и увеличило APK. Версия фиксируется в version catalog.

Если обязательная проверка CIO не проходит воспроизводимо, change завершается verdict=replace. Альтернативный engine или другая серверная библиотека выбираются отдельным OpenSpec change за тем же интерфейсом adapter.

### 2. Spike изолируется build variant и архитектурным интерфейсом

Диагностический UI, маршруты, manifest overlay и их composition root размещаются в app/src/debug. Ktor-зависимости подключаются через debugImplementation. Release source set не ссылается на эти типы.

Управление выполняется через небольшой интерфейс EmbeddedServerController и неизменяемое состояние ServerState. UI вызывает controller и отображает StateFlow, но не обращается к Ktor напрямую. Это сохраняет направление зависимостей Clean Architecture и принцип инверсии зависимостей, не создавая преждевременные Gradle-модули.

Hilt в spike не добавляется. Короткоживущий debug composition root создаёт зависимости вручную. Hilt вводится в следующем production change после положительного решения по server adapter.

### 3. Диагностическая сеть отделена от production-протокола

Все маршруты имеют префикс /diagnostics; пространство /api/v1 остаётся пустым до отдельного change. Сервер слушает 0.0.0.0 на явно отображаемом свободном порту.

В debug manifest объявляются INTERNET и ACCESS_LOCAL_NETWORK. На API 37.1 harness проверяет и запрашивает ACCESS_LOCAL_NETWORK до LAN-проверки; на API 29 эта ветка не выполняется. Для воспроизводимого доступа с компьютера к AVD используется задокументированный adb forward. Никаких исходящих запросов во внешний интернет сервер не выполняет.

При каждом старте генерируется криптографически случайный токен, который живёт только в памяти и отображается в harness. HTTP и WebSocket handshake используют Authorization: Bearer. Это не production pairing, а защита диагностического порта от случайного доступа в локальной сети.

### 4. Lifecycle реализуется как сериализованная state machine

Операции start и stop сериализуются Mutex. Повторный start во время Running и повторный stop во время Stopped идемпотентны. Controller владеет engine, SupervisorJob и дочерними сессиями и освобождает их в NonCancellable cleanup с ограниченным timeout.

После bind server получает фактический порт и только затем публикует Running. Ошибка bind переводит состояние в Error, сохраняет безопасное сообщение и не делает controller непригодным для следующего start.

### 5. Диагностические payload детерминированы

- GET /diagnostics/health возвращает status, ktorVersion, engine, sdkInt и uptimeMs.
- /diagnostics/ws принимает сериализованный объект с id и payload и возвращает тот же объект.
- POST /diagnostics/upload читает ByteReadChannel фиксированными блоками, обновляет SHA-256 и счётчик, затем возвращает digest и bytesReceived.
- GET /diagnostics/download генерирует детерминированную последовательность блоков на лету и сообщает ожидаемые размер и SHA-256 заголовками.

Ни один маршрут не использует readBytes для полного тела и не создаёт ByteArray, зависящий от общего размера 500 МБ.

### 6. Проверки делятся на быстрые и Android-runtime

JVM unit tests покрывают state transitions, генератор блоков, SHA-256 и проверку токена до реализации соответствующего кода. Instrumented tests поднимают настоящий CIO engine и проверяют lifecycle, HTTP, WebSocket, streaming и cancellation.

Одинаковые instrumented tests запускаются последовательно на Pixel AVD API 29 и Pixel AVD API 37.1. Доступ с компьютера через adb forward проверяется отдельной воспроизводимой командой, потому что это граница между host и Android-процессом.

### 7. Метрики и критерии решения фиксируются до прогона

Перед добавлением Ktor сохраняются размер и SHA-256 базового debug APK. После реализации тем же Gradle/JDK окружением собирается новый debug APK; отчёт содержит абсолютные размеры и delta. Release APK отдельно проверяется на отсутствие диагностических классов, ресурсов, токена и строк маршрутов. Финальный production size budget будет повторно измерен на release-этапе.

Во время streaming-тестов sampler записывает Java heap и Android Debug.MemoryInfo totalPss до передачи, в пике и через 30 секунд после завершения. Для результата accept одновременно выполняются условия:

- upload и download по 500 МБ завершаются с правильным SHA-256 без OOM;
- пиковый прирост totalPss относительно устойчивого baseline не превышает 128 MiB;
- через 30 секунд остаточный прирост totalPss не превышает 32 MiB;
- после cancellation и каждого stop следующий health-check/start остаётся успешным.

Если метрика нестабильна, выполняются три одинаковых прогона и в отчёт берётся медиана с сохранением всех результатов.

### 8. Отчёт является частью результата change

docs/spikes/embedded-ktor-server.md содержит commit, JDK/Gradle/AGP, Ktor/engine, AVD image/ABI/RAM, команды, таблицу обоих API, метрики памяти, APK delta, известные ограничения и единственный verdict accept или replace.

## Risks and Trade-offs

- AVD не доказывает поведение всех физических Wi-Fi чипов. Spike проверяет Android runtime и host-доступ; физическое устройство остаётся рекомендуемой дополнительной проверкой перед релизом.
- adb forward обходит часть реального LAN-маршрута. Поэтому API 37.1 отдельно проверяет permission flow, а полноценная работа в одной Wi-Fi сети входит в change жизненного цикла.
- Debug APK больше release APK. Delta используется для раннего сравнения зависимостей, а не как окончательный production budget.
- PSS зависит от AVD и фоновой нагрузки. Фиксированное окружение, baseline и медиана трёх прогонов уменьшают шум.
- Диагностический bearer-токен не является моделью production pairing. Его нельзя переносить в /api/v1 без отдельного security design.

## Rollback Plan

При verdict=replace удаляются debug Ktor dependencies, harness и диагностические маршруты. Сохраняются только отчёт, тестовая матрица и нейтральный интерфейс server adapter, если он не зависит от Ktor. Следующий change предлагает альтернативный adapter без изменения domain и будущего /api/v1.

## References

- Ktor releases: https://ktor.io/docs/releases.html
- Ktor server engines: https://ktor.io/docs/server-engines.html
- Ktor WebSockets: https://ktor.io/docs/server-websockets.html
- Android 17 local network permission: https://developer.android.com/about/versions/17/behavior-changes-17
