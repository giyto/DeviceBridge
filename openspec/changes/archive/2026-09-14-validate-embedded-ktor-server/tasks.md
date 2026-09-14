## 1. Зафиксировать baseline и изоляцию spike

- [x] 1.1 Собрать исходные debug и release APK до добавления Ktor, записать их размер, SHA-256, commit, JDK и Gradle в черновик docs/spikes/embedded-ktor-server.md и проверить, что все baseline-поля заполнены воспроизводимыми командами
- [x] 1.2 Зафиксировать Ktor 3.5.2 в version catalog, добавить через debugImplementation только server-core, server-cio, server-websockets и serialization, затем проверить dependencyInsight: зависимости присутствуют в debugRuntimeClasspath и отсутствуют в releaseRuntimeClasspath
- [x] 1.3 Добавить debug manifest overlay с INTERNET, ACCESS_LOCAL_NETWORK и разрешением cleartext только для spike, затем собрать debug/release и проверить merged manifest обоих вариантов
- [x] 1.4 Добавить debug-only точку входа в диагностический экран и проверить анализом release APK, что в release отсутствуют экран, токен и строки /diagnostics/*

## 2. Реализовать управляемый server adapter через TDD

- [x] 2.1 Сначала написать unit-тесты переходов Stopped/Starting/Running/Stopping/Error и идемпотентных start/stop, убедиться, что новые тесты падают, затем реализовать ServerState и EmbeddedServerController до прохождения тестов
- [x] 2.2 Сначала добавить тест конкурентных start/stop, воспроизвести гонку, затем сериализовать операции Mutex и проверить стабильное прохождение теста не менее 100 повторений
- [x] 2.3 Сначала добавить тест ошибки bind и повторного запуска, затем реализовать Ktor/CIO adapter с освобождением engine, coroutine scope и порта и проверить переход Error → Running без перезапуска приложения
- [x] 2.4 Реализовать криптографически случайный in-memory токен на каждый start и проверить unit-тестами смену токена после restart и невозможность получить прежний токен после stop

## 3. Добавить защищённые диагностические HTTP и WebSocket

- [x] 3.1 Сначала написать instrumented-тесты GET /diagnostics/health для валидного, отсутствующего и неверного bearer-токена, затем реализовать маршрут до ответов 200/401 и проверить состав JSON
- [x] 3.2 Сначала написать instrumented-тест обмена 1000 WebSocket-сообщениями, затем реализовать /diagnostics/ws и проверить точное совпадение количества, порядка, id и payload
- [x] 3.3 Добавить instrumented-тест аварийного разрыва WebSocket, затем реализовать cleanup сессии и проверить успешное подключение нового клиента
- [x] 3.4 Реализовать debug harness с start/stop/restart, состоянием, адресом, портом, токеном и безопасной ошибкой и проверить вручную все состояния, включая отказ ACCESS_LOCAL_NETWORK на API 37.1

## 4. Доказать потоковую передачу 500 МБ

- [x] 4.1 Сначала написать unit-тесты детерминированного генератора блоков и инкрементального SHA-256 на разных размерах и границах chunk, затем реализовать их без массива размером с payload и проверить тесты
- [x] 4.2 Сначала написать instrumented-тест upload на малом потоке и 500 МБ, затем реализовать POST /diagnostics/upload через ByteReadChannel и проверить bytesReceived и SHA-256 без сохранения пользовательского файла
- [x] 4.3 Сначала написать instrumented-тест download на малом потоке и 500 МБ, затем реализовать GET /diagnostics/download с генерацией chunk-by-chunk и проверить точный размер и SHA-256 на клиенте
- [x] 4.4 Сначала написать instrumented-тесты отмены upload/download, затем реализовать cooperative cancellation и cleanup и проверить успешный health-check сразу после каждой отмены

## 5. Автоматизировать lifecycle и метрики

- [x] 5.1 Добавить instrumented-тест 20 циклов start → health → stop и проверить, что все циклы завершаются, состояние возвращается в Stopped, а занятый порт можно повторно привязать
- [x] 5.2 Добавить sampler Java heap и Debug.MemoryInfo totalPss с baseline, peak и значением через 30 секунд и проверить, что результаты каждого streaming-прогона сохраняются в machine-readable test output
- [x] 5.3 Добавить режим трёх повторов и расчёт медианы для нестабильных метрик и проверить unit-тестом расчёт медианы и формирование итоговой строки
- [x] 5.4 Документировать adb forward и авторизованный health-check с компьютера и проверить команды на запущенном AVD без обращения к внешнему backend

## 6. Выполнить обязательную матрицу API 29 и API 37.1

- [x] 6.1 Запустить .\gradlew.bat :app:testDebugUnitTest :app:lintDebug и проверить BUILD SUCCESSFUL без пропущенных обязательных тестов
- [x] 6.2 На Pixel AVD API 29 выполнить полный :app:connectedDebugAndroidTest, host health-check и три streaming-прогона при необходимости, затем внести версии образа, ABI, RAM, результаты и метрики в отчёт
- [x] 6.3 На Pixel AVD API 37.1 проверить grant и denial ACCESS_LOCAL_NETWORK, выполнить тот же :app:connectedDebugAndroidTest, host health-check и streaming-прогоны, затем внести результаты и метрики в отчёт
- [x] 6.4 Повторно собрать debug/release APK тем же окружением, вычислить размер и SHA-256, записать delta относительно baseline и проверить release APK на отсутствие диагностических ресурсов, классов и строк

## 7. Зафиксировать техническое решение

- [x] 7.1 Заполнить итоговую таблицу docs/spikes/embedded-ktor-server.md для обоих API, включая HTTP, WebSocket, lifecycle, upload/download/cancellation, PSS, APK delta и ограничения, и проверить отсутствие незаполненных значений
- [x] 7.2 Установить ровно один verdict=accept или verdict=replace по критериям design.md, добавить фактическое обоснование и проверить, что при replace дальнейший production-server change явно помечен заблокированным
- [x] 7.3 Выполнить openspec validate validate-embedded-ktor-server --strict и проверить успешную валидацию change перед review, commit и push
