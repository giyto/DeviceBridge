## ADDED Requirements

### Requirement: Android file draft редактируется до отправки

Android-приложение MUST хранить выбранные через picker или share preview файлы в отдельном draft до подтверждения отправки. Пользователь MUST иметь возможность удалить один item, очистить draft и добавить другие файлы; эти действия MUST NOT удалять или изменять исходные документы.

#### Scenario: Удалён один выбранный файл
- **WHEN** пользователь удаляет item из file preview до подтверждения
- **THEN** item исчезает только из draft
- **AND** остальные выбранные файлы, recipient и исходный документ остаются без изменений

#### Scenario: Пользователь добавляет другие файлы
- **WHEN** пользователь повторно открывает picker и выбирает допустимые документы
- **THEN** новые items добавляются к оставшимся draft items в стабильном порядке
- **AND** повторно выбранный тот же документ не создаёт неразличимый дубликат

#### Scenario: Повторный picker отменён
- **WHEN** пользователь закрывает picker без выбора
- **THEN** существующий file draft сохраняется
- **AND** приложение не начинает отправку и не возвращается с crash

#### Scenario: Draft очищен
- **WHEN** пользователь удаляет последний item или выбирает «Очистить»
- **THEN** preview показывает empty state, а действие отправки становится недоступным
- **AND** пользователь может открыть picker и создать новый draft

### Requirement: Раздел истории показывает сохранённые операции

Android shell MUST отображать records из локальной history capability, поддерживать фильтры, детали, удаление отдельной записи и полную очистку с подтверждением.

#### Scenario: Пользователь открывает историю
- **WHEN** history repository содержит records
- **THEN** экран показывает фактические records от новых к старым
- **AND** выбранные фильтры отражаются в UiState и переживают configuration change

#### Scenario: Пользователь удаляет историю
- **WHEN** пользователь подтверждает удаление одной record или всех records
- **THEN** экран обновляется по фактическому результату repository
- **AND** не показывает ложный успех при ошибке storage

### Requirement: Раздел настроек управляет локальными параметрами

Android shell MUST предоставлять доступные controls для имени телефона, destination folder, retention period, file size limit и списка trusted browsers. Экран MUST показывать сохранённые значения и отдельные состояния сохранения и ошибки.

#### Scenario: Настройка сохранена
- **WHEN** пользователь вводит допустимое значение и подтверждает изменение
- **THEN** экран показывает значение, подтверждённое settings repository
- **AND** оно не откатывается после возврата на экран

#### Scenario: Значение недопустимо
- **WHEN** validation отклоняет введённую настройку
- **THEN** экран сохраняет редактируемое значение для исправления и показывает понятную причину
- **AND** не заменяет рабочую настройку недопустимым значением

#### Scenario: Trusted browser отозван
- **WHEN** пользователь подтверждает отзыв browser entry
- **THEN** список обновляется после фактического отзыва credential
- **AND** entry не исчезает заранее при ошибке операции

### Requirement: DeviceBridge использует собственную launcher icon

Android APK MUST содержать собственную иконку DeviceBridge вместо стандартной template icon. Ресурсы MUST включать adaptive foreground/background, совместимый launcher fallback для API 29 и monochrome layer для платформ, поддерживающих themed icons; app label MUST быть «DeviceBridge».

#### Scenario: Приложение установлено на API 29
- **WHEN** launcher отображает установленный DeviceBridge
- **THEN** пользователь видит узнаваемую legacy/adaptive иконку и label «DeviceBridge»
- **AND** стандартная template icon Android Studio не используется

#### Scenario: Launcher применяет системную маску
- **WHEN** adaptive icon отображается круглой, squircle или другой системной маской
- **THEN** основной знак остаётся внутри safe zone и не обрезается
- **AND** фон заполняет всю маску без внешнего сетевого ресурса

#### Scenario: Включены тематические иконки
- **WHEN** версия Android и launcher поддерживают monochrome themed icons
- **THEN** DeviceBridge предоставляет monochrome layer
- **AND** иконка остаётся различимой в системной палитре
