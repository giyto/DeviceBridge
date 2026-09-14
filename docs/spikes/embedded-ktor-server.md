# Embedded Ktor Server Spike

## Статус

- Change: validate-embedded-ktor-server
- Candidate: Ktor 3.5.2 / CIO
- Verdict: accept

## Baseline до добавления Ktor

- Commit: 4e4192b210a5690e43cc09a6140452de461ce45d
- ОС: Windows 11 10.0 amd64
- Gradle: 9.5.0
- Launcher JVM: Eclipse Adoptium 21.0.7+6-LTS
- Android Gradle Plugin: 9.3.2
- Kotlin Android plugin: 2.2.10
- Команды сборки:
  - .\gradlew.bat :app:assembleDebug
  - .\gradlew.bat :app:assembleRelease
  - Get-FileHash -Algorithm SHA256 <apk>

| APK | Размер, байт | SHA-256 |
| --- | ---: | --- |
| app-debug.apk | 29 812 381 | BCBB059DAB9A9E4478217B2B7BBE74A100EEF08C50BAFEA94640518E0D1C3F40 |
| app-release-unsigned.apk | 23 019 013 | 589F1412592CE519976D53F765D12B549B7C6EFECD838E8C1D6D7C093AF57BEC |

## Проверяемая конфигурация

| Параметр | API 29 | API 37.1 |
| --- | --- | --- |
| AVD | Pixel_4 | Pixel_8_API_37_1_DeviceBridge |
| System image | Android 10, QSR1.211112.011, build 13135432 | Android 17, CP31.260623.012, build 16064790, 16 KB page image |
| ABI | x86_64 | x86_64 |
| RAM | 2 040 248 KiB | 4 008 368 KiB |
| ACCESS_LOCAL_NETWORK grant | не применяется | PASS: permission granted, server and host health available |
| ACCESS_LOCAL_NETWORK denial | не применяется | PASS: system denial leaves permission revoked and shows a controlled error |

## Результаты

| Проверка | API 29 | API 37.1 |
| --- | --- | --- |
| 20 циклов lifecycle | PASS | PASS |
| Авторизованный HTTP health | PASS | PASS |
| HTTP без токена / неверный токен | PASS: 401 | PASS: 401 |
| WebSocket 1000 сообщений | PASS: порядок, id и payload совпали | PASS: порядок, id и payload совпали |
| WebSocket abrupt disconnect | PASS: повторное подключение успешно | PASS: повторное подключение успешно |
| Upload 500 МБ + SHA-256 | PASS | PASS |
| Download 500 МБ + SHA-256 | PASS | PASS |
| Cancellation + recovery | PASS: health доступен после обеих отмен | PASS: health доступен после обеих отмен |
| Host health через adb forward | PASS: HTTP 200, sdkInt=29 | PASS: HTTP 200, sdkInt=37 |

На каждом AVD команда `./gradlew.bat :app:connectedDebugAndroidTest` завершила 17 из 17 тестов без ошибок и пропусков. На API 29 прогон занял 2 мин 13 с, на API 37.1 — 3 мин 16 с.

## Метрики памяти

| API | Операция | Прогон | Baseline PSS | Peak PSS | PSS через 30 с | Peak delta | Retained delta | Java heap peak |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 29 | upload 500 МБ | 1 | 73 747 KiB | 105 882 KiB | 77 545 KiB | 31,38 MiB | 3,71 MiB | 36 841 336 B |
| 29 | download 500 МБ | 1 | 70 652 KiB | 79 099 KiB | 43 193 KiB | 8,25 MiB | -26,82 MiB | 29 828 600 B |
| 37.1 | upload 500 МБ | 1 | 98 475 KiB | 106 720 KiB | 89 187 KiB | 8,05 MiB | -9,07 MiB | 21 492 768 B |
| 37.1 | download 500 МБ | 1 | 96 807 KiB | 106 739 KiB | 91 046 KiB | 9,70 MiB | -5,63 MiB | 23 327 776 B |

Критерии accept: peak totalPss delta не более 128 MiB, retained delta через 30 секунд не более 32 MiB, отсутствие OOM и успешное восстановление после cancellation. Все четыре измерения прошли с большим запасом, поэтому повторный трёхкратный прогон для стабилизации пограничного результата не потребовался. Режим трёх повторов и расчёт медианы доступны через instrumentation argument `deviceBridgeMetricRuns=3`.

## APK после spike

| APK | Размер, байт | Delta к baseline | SHA-256 |
| --- | ---: | ---: | --- |
| app-debug.apk | 36 876 484 | +7 064 103 | 390C1FBAA9675810D4497908434E904C5EAAB5F1F9B177392464639B923E781F |
| app-release-unsigned.apk | 23 019 013 | 0 | 589F1412592CE519976D53F765D12B549B7C6EFECD838E8C1D6D7C093AF57BEC |

Release APK проверен через `apkanalyzer`: совпадений для пакета diagnostics, DiagnosticsActivity, EmbeddedServer, io.ktor, ACCESS_LOCAL_NETWORK и строк `/diagnostics/` нет. Размер и SHA-256 release APK совпали с baseline.

## Проверка с компьютера

Диагностический сервер использует фиксированный порт 8787. Для AVD соединение с компьютера настраивается так:

1. Установить debug APK и запустить debug-only Activity:

       D:\Android\Sdk\platform-tools\adb.exe install -r app\build\outputs\apk\debug\app-debug.apk
       D:\Android\Sdk\platform-tools\adb.exe shell am start -a ru.hznik.devicebridge.action.OPEN_DIAGNOSTICS -p ru.hznik.devicebridge

2. На экране Server Lab нажать «Запустить сервер» и скопировать Bearer token.

3. Пробросить порт AVD на компьютер:

       D:\Android\Sdk\platform-tools\adb.exe forward tcp:8787 tcp:8787

4. Выполнить авторизованный health-check из PowerShell:

       $headers = @{ Authorization = "Bearer <TOKEN_FROM_SERVER_LAB>" }
       Invoke-RestMethod -Uri "http://127.0.0.1:8787/diagnostics/health" -Headers $headers

Ожидается HTTP 200 и JSON с status=ok, ktorVersion=3.5.2, engine=CIO, sdkInt и uptimeMs. Запрос без заголовка или с неверным токеном должен вернуть HTTP 401 без диагностических полей.

5. После проверки удалить port forwarding:

       D:\Android\Sdk\platform-tools\adb.exe forward --remove tcp:8787

Маршруты spike не выполняют исходящих запросов и не используют внешний backend.

## Ограничения и решение

- Известные ограничения:
  - spike работает только в debug variant и использует cleartext HTTP на фиксированном порту 8787;
  - измерения выполнены на AVD, а не на физических телефонах и реальном Wi-Fi-роутере;
  - не проверялись длительная работа в фоне, Doze, firewall/router isolation и смена сети;
  - токен живёт только в памяти и показывается на диагностическом экране; production-аутентификация и pairing будут определены отдельным change.
- Verdict: accept.
- Обоснование: Ktor 3.5.2 / CIO стабильно прошёл обязательную матрицу API 29 и API 37.1, HTTP/WebSocket/lifecycle/cancellation, потоковые upload и download по 500 МБ с проверкой SHA-256 и оба лимита памяти. Debug-only изоляция подтверждена неизменившимися размером и SHA-256 release APK и нулевыми совпадениями диагностических сущностей в анализе APK. Следующий server change можно строить поверх `EmbeddedServerController`, не перенося диагностические маршруты в production API.
