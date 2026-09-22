# DeviceBridge: подпись release-сборки

Release APK подписывается только ключом, который находится вне repository. Debug-key,
пароли, fingerprint сертификата и локальный путь к keystore не добавляются в Git,
отчёты или команды, сохраняемые в документации.

## Внешние параметры

Gradle ожидает четыре свойства:

- `deviceBridgeReleaseStoreFile`;
- `deviceBridgeReleaseStorePassword`;
- `deviceBridgeReleaseKeyAlias`;
- `deviceBridgeReleaseKeyPassword`.

В локальной PowerShell-сессии их безопаснее передать через временные переменные
окружения. Значения ниже являются placeholders и должны быть заменены только в
локальной консоли или secret store CI:

```powershell
$env:ORG_GRADLE_PROJECT_deviceBridgeReleaseStoreFile = '<absolute-path-to-keystore>'
$env:ORG_GRADLE_PROJECT_deviceBridgeReleaseStorePassword = '<store-password>'
$env:ORG_GRADLE_PROJECT_deviceBridgeReleaseKeyAlias = '<key-alias>'
$env:ORG_GRADLE_PROJECT_deviceBridgeReleaseKeyPassword = '<key-password>'
```

Не передавайте реальные значения через параметры командной строки и не записывайте
их в `gradle.properties` внутри repository. `*.jks` и `*.keystore` исключены из Git.

## Сборка distribution artifact

Для сборки с секретами отключается configuration cache:

```powershell
.\gradlew.bat --no-configuration-cache :app:assembleDistributionRelease
```

`assembleDistributionRelease` сначала выполняет
`verifyReleaseSigningConfiguration`. Отсутствующее свойство или несуществующий
keystore останавливает сборку; fallback на debug-подпись отсутствует.

После сборки удалите секреты из текущей сессии и остановите Gradle daemon:

```powershell
Remove-Item Env:ORG_GRADLE_PROJECT_deviceBridgeReleaseStoreFile
Remove-Item Env:ORG_GRADLE_PROJECT_deviceBridgeReleaseStorePassword
Remove-Item Env:ORG_GRADLE_PROJECT_deviceBridgeReleaseKeyAlias
Remove-Item Env:ORG_GRADLE_PROJECT_deviceBridgeReleaseKeyPassword
.\gradlew.bat --stop
```

## Проверка кандидата

Путь к APK берётся из `app/build/outputs/apk/release/`. Для публикуемого artifact
обязательны:

```powershell
& "$env:ANDROID_HOME\build-tools\36.0.0\apksigner.bat" verify --verbose --print-certs <release-apk>
.\gradlew.bat :app:generateReleaseMetadata
```

В release evidence фиксируются только version metadata, commit, SHA-256 APK,
`webAssetVersion`, результат `apksigner` и факт совместимости сертификата с
предыдущей версией. Сам fingerprint допускается хранить только в защищённом
release-процессе, если это требуется политикой владельца ключа.

При `versionCode > 1` задайте внешний
`deviceBridgePreviousVersionCode`; новая версия обязана быть строго больше
предыдущей. Пока APK unsigned, worktree dirty или `apksigner` не подтверждён,
artifact имеет статус `BLOCKED` и не публикуется.
