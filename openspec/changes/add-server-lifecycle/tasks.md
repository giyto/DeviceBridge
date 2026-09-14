## 1. Зафиксировать baseline и подготовить composition root

- [ ] 1.1 Зафиксировать в docs/verification/server-lifecycle.md текущие результаты unit/instrumented/web-тестов, размеры debug/release APK и SHA-256 артефактов; проверить, что baseline воспроизводится до функциональных изменений.
- [ ] 1.2 Добавить в version catalog совместимые с Kotlin 2.2.10 и AGP 9.3.2 версии Hilt и KSP, перевести Java/Kotlin target на 17 и подключить processors; подтвердить чистой Gradle-синхронизацией и компиляцией debug/release.
- [ ] 1.3 Сначала добавить failing graph tests, затем создать DeviceBridgeApplication, Hilt entry points для MainActivity и будущего service, application-scoped bindings и минимальные modules; проверить создание debug и release dependency graph.
- [ ] 1.4 Сначала зафиксировать manifest assertions, затем объявить INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE, ACCESS_LOCAL_NETWORK, POST_NOTIFICATIONS, FOREGROUND_SERVICE, FOREGROUND_SERVICE_CONNECTED_DEVICE и CHANGE_NETWORK_STATE; проверить merged manifests обеих сборок.
- [ ] 1.5 Добавить manifest-тест, подтверждающий, что production service не экспортирован, имеет тип connectedDevice, а boot receiver отсутствует; проверить тест на API 29 и API 37.1.

## 2. Реализовать независимый domain lifecycle через TDD

- [ ] 2.1 Сначала написать unit-тесты контрактов, затем добавить неизменяемые ServerEndpoint, ServerLifecycleState, ServerLifecycleError и причины остановки без импортов Android, Ktor и Compose; проверить архитектурным тестом границы domain.
- [ ] 2.2 Сначала написать тесты публичных команд, затем определить ServerLifecycleRepository и use cases запуска, остановки и наблюдения за состоянием; проверить, что UI зависит только от domain-контрактов.
- [ ] 2.3 Сначала написать RED-тесты полного графа переходов Stopped, Starting, Running, Stopping и Error, затем реализовать чистый reducer; проверить запрет недопустимых переходов.
- [ ] 2.4 Сначала покрыть конкурентные и повторные команды, затем реализовать mutex-сериализацию и generation id; проверить, что параллельные start/stop создают не более одного runtime и устаревшие callbacks игнорируются.
- [ ] 2.5 Сначала покрыть частично успешный startup и повторный cleanup, затем реализовать идемпотентное освобождение runtime, jobs и observers; проверить повторный запуск после каждой recoverable error.
- [ ] 2.6 Сначала написать тесты fake monotonic clock, затем вычислять uptime от фактического Running timestamp; проверить, что wall-clock change и пересоздание UI не обнуляют и не искажают uptime.

## 3. Реализовать разрешения и выбор локальной сети

- [ ] 3.1 Сначала написать parameterized-тесты API 29, 33 и 37, затем реализовать permission policy: ACCESS_LOCAL_NETWORK обязателен только на API 37+, а отказ POST_NOTIFICATIONS не блокирует разрешённый platform-ой foreground start.
- [ ] 3.2 Сначала добавить тесты grant, deny, retry и permanent-denial состояний, затем реализовать Android permission gateway и одноразовые UI effects через Activity Result API; проверить отсутствие запроса LAN permission на API 29.
- [ ] 3.3 Сначала написать тест отзыва разрешения во время Running, затем реализовать UID permission listener и fail-closed обработку SecurityException; проверить, что listener снимается, listener/socket закрываются и требуется явный повторный запуск.
- [ ] 3.4 Сначала написать fake-snapshot тесты Wi-Fi, hotspot, AVD, отсутствующей и неоднозначной сети, затем реализовать LanEndpointResolver с приоритетом active Wi-Fi LinkProperties.
- [ ] 3.5 Добавить RED-тесты фильтрации loopback, link-local, multicast, unspecified, IPv6 и лишних virtual/VPN candidates, затем реализовать ограниченный private-IPv4 interface fallback; проверить, что неоднозначный результат возвращает recoverable error, а не случайный адрес.
- [ ] 3.6 Реализовать Android adapters для ConnectivityManager, LinkProperties и NetworkInterface за тестируемыми интерфейсами; проверить instrumented-тестом, что пользовательский endpoint никогда не содержит 0.0.0.0 или 127.0.0.1.
- [ ] 3.7 Сначала написать тесты регистрации, unregister и поздних callbacks, затем реализовать один LanNetworkObserver на lifecycle, использующий данные аргументов NetworkCallback; проверить отсутствие синхронных повторных network queries внутри callbacks.
- [ ] 3.8 Сначала покрыть потерю сети, disappearance адреса и смену fingerprint, затем связать observer с coordinator; проверить остановку старого listener и отсутствие автоматического запуска на новой сети.

## 4. Перенести безопасную часть Ktor server в production

- [ ] 4.1 Сначала добавить contract tests production runtime, затем выделить интерфейсы server runtime и перенести только безопасные Ktor/CIO primitives из debug в main source set; проверить, что debug diagnostics остаются отдельной надстройкой.
- [ ] 4.2 Перевести Ktor/CIO/serialization и нужные web-serving зависимости из debugImplementation в production dependency scope; проверить assembleDebug, assembleRelease и отсутствие duplicate classes.
- [ ] 4.3 Сначала написать release route tests, затем подключить к production composition только /, разрешённые /assets/* и /web-manifest.json с существующими CSP, cache, Host и Origin правилами; проверить offline-работу без внешних запросов.
- [ ] 4.4 Сначала написать тест динамического bind, затем открывать all-local socket на порту 0 и публиковать выбранный LAN IPv4 с фактически занятым портом; проверить освобождение порта после stop.
- [ ] 4.5 Сначала написать negative release tests, затем гарантировать 404 для /diagnostics/* и незавершённых /api/v1/* и отсутствие diagnostic token в release graph/APK; отдельно проверить, что debug diagnostics по-прежнему работают.
- [ ] 4.6 Добавить integration-тест 20 последовательных циклов start → web-manifest → stop для production runtime; проверить единственный listener, корректный manifest и успешное повторное занятие порта во всех циклах.

## 5. Добавить foreground service и управляемое уведомление

- [ ] 5.1 Сначала написать тесты notification model, затем реализовать идемпотентный low-priority channel и состояния «Запускается», «Запущен» и «Останавливается»; проверить endpoint, 0 браузеров, отсутствие передачи и отсутствие секретов.
- [ ] 5.2 Сначала зафиксировать service startup test, затем реализовать неэкспортируемый ServerForegroundService: немедленный ServiceCompat.startForeground с типом connectedDevice, запуск coordinator и START_NOT_STICKY; проверить системный deadline на API 29 и API 37.1.
- [ ] 5.3 Сначала написать тест PendingIntent, затем добавить immutable notification action «Остановить», направленный в тот же coordinator; проверить закрытие listener/clients/jobs/observers, удаление notification и stopSelf.
- [ ] 5.4 Обработать ForegroundServiceStartNotAllowedException, SecurityException, bind failure и timeout остановки единым cleanup/error path; проверить, что main thread не блокируется и следующий явный start доступен.
- [ ] 5.5 Связать notification controller только с фактическим process-wide state; проверить, что позднее или восстановленное UI-состояние не создаёт ложное уведомление и не публикует устаревший endpoint.
- [ ] 5.6 Добавить instrumented-тесты ухода Activity в фон, пересоздания Activity и возврата к тому же service instance; проверить отсутствие второго socket и сохранение фактического uptime.

## 6. Подключить lifecycle к главному экрану Compose

- [ ] 6.1 Сначала написать HomeViewModel unit-тесты actions, state и одноразовых permission effects, затем подключить use cases через Hilt; проверить, что ViewModel не импортирует Ktor и не управляет service напрямую.
- [ ] 6.2 Сначала добавить Compose-тесты Stopped, Starting, Running, Stopping и Error, затем реализовать соответствующие карточки и доступность кнопок; проверить блокировку конфликтующих повторных команд.
- [ ] 6.3 Добавить отображение только фактического LAN URL, копирование адреса с доступным label и живой uptime; проверить ClipboardManager, content descriptions и отсутствие endpoint вне Running.
- [ ] 6.4 Реализовать объяснение и flow LAN permission, повтор после denial/revoke и неблокирующее предупреждение об отключённых уведомлениях; проверить UI-тестами все ветки на API 29 и API 37.1.
- [ ] 6.5 Сохранить «Текст» и «Файлы» недоступными при Stopped и при Running без pairing, с объяснением следующего этапа; проверить Compose-тестами, светлой/тёмной темой и увеличенным шрифтом.

## 7. Проверить системные сценарии и реальную LAN-доступность

- [ ] 7.1 На AVD API 29 выполнить полный сценарий start, открытие web shell/web-manifest через adb forward, background/recreation, stop из UI и notification и 20 циклов; сохранить результаты в verification report.
- [ ] 7.2 На AVD API 37.1 проверить grant, denial, retry и revoke ACCESS_LOCAL_NETWORK, grant/denial POST_NOTIFICATIONS, start/stop и отсутствие crash; сохранить результаты и снимки фактического state.
- [ ] 7.3 На обеих API проверить принудительное завершение процесса/Task Manager и новый запуск приложения; подтвердить отсутствие скрытого восстановления Running, socket и notification.
- [ ] 7.4 Проверить потерю Wi-Fi и смену IPv4 во время Running; подтвердить закрытие старого URL, recoverable error и отсутствие автоматического перезапуска.
- [ ] 7.5 На физическом телефоне открыть показанный Wi-Fi URL с компьютера в Chrome и Edge без adb forward; проверить root, assets, web-manifest, копирование URL и отсутствие внешнего backend.
- [ ] 7.6 На физическом телефоне включить hotspot, подключить компьютер и открыть показанный hotspot URL в Chrome и Edge; подтвердить достижимость адреса и корректную остановку server.

## 8. Завершить проверку change

- [ ] 8.1 Повторно измерить debug/release APK и SHA-256, сравнить с baseline и через APK Analyzer подтвердить ожидаемое наличие production Ktor/CIO и отсутствие diagnostics/token; обновить verification report.
- [ ] 8.2 Провести архитектурную проверку: одно Android-приложение, без desktop/backend, domain без Android/Ktor, ViewModel без service control, Hilt как composition root, без pairing/transfer/DataStore/boot autostart; зафиксировать результат.
- [ ] 8.3 Выполнить web tests/build, Gradle unit tests, lint, assembleDebug, assembleRelease и connected tests на API 29/API 37.1; устранить ошибки и записать точные команды и результаты.
- [ ] 8.4 Сверить реализованное поведение с docs/technical-specification.md, openspec/roadmap.md, proposal.md, design.md и обеими delta specs; обновить verification report без расширения scope следующего pairing change.
- [ ] 8.5 Выполнить openspec validate add-server-lifecycle --strict, передать пользователю пошаговый ручной чек-лист и после его подтверждения синхронизировать main specs, архивировать change, создать локальный commit и push в GitHub.
