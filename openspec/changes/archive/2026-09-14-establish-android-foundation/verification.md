# Verification notes

## Automated checks

Проверено 13–14 сентября 2026 года:

- `:app:testDebugUnitTest` — `BUILD SUCCESSFUL`;
- `:app:lintDebug :app:assembleDebug` — `BUILD SUCCESSFUL`;
- `:app:connectedDebugAndroidTest` на
  `Pixel_8_API_37_1_DeviceBridge (AVD), Android 17` — 9/9 тестов прошли.
- `:app:connectedDebugAndroidTest` на
  `Pixel_4_API_29_DeviceBridge (AVD), Android 10 / API 29` — тесты прошли.

Автоматические проверки выполнены на обеих целевых границах API.

Run from the repository root:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
.\gradlew.bat :app:connectedDebugAndroidTest
```

Run `connectedDebugAndroidTest` separately while only the API 29 AVD is online,
then again while only the API 37.1 AVD is online.

## Manual accessibility and UI checklist

- [x] Open all three sections in the light system theme.
- [x] Open all three sections in the dark system theme.
- [x] Set the Android font size to 200% and confirm that every screen remains
      readable and scrollable without clipped controls.
- [x] Enable TalkBack and confirm that the bottom navigation announces
      «Главная», «История» and «Настройки» together with the selected state.
- [x] Confirm that the home screen says «Сервер остановлен» and does not show an
      address, pairing code or connected browser.
- [x] Confirm that «Запустить сервер», «Текст» and «Файлы» are disabled.
- [x] Confirm that history shows only the honest empty state.

Ручная проверка выполнена владельцем проекта 14 сентября 2026 года на AVD
API 37.1: замечаний не обнаружено.

Record the device/API and the result beside each item before marking tasks 4.3,
5.1 and 5.2 complete.
