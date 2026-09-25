package ru.hznik.devicebridge.feature.settings

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ru.hznik.devicebridge.core.text.formatDayAndMinute
import ru.hznik.devicebridge.core.ui.DestructiveActionButton
import ru.hznik.devicebridge.domain.settings.IdleStopTimeout
import ru.hznik.devicebridge.domain.settings.ThemePreference
import ru.hznik.devicebridge.core.ui.ScreenHeader
import ru.hznik.devicebridge.core.ui.SectionHeader
import ru.hznik.devicebridge.core.ui.TonalActionButton
import ru.hznik.devicebridge.core.ui.dismissKeyboardOnUnconsumedTap
@Composable
fun SettingsScreen(
    uiState: SettingsUiState,
    onAction: (SettingsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("settings-list")
            .dismissKeyboardOnUnconsumedTap()
            .imePadding()
            .padding(horizontal = 20.dp),
        contentPadding = PaddingValues(vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            ScreenHeader(
                title = "Настройки",
                modifier = Modifier.semantics {
                    contentDescription = "Заголовок экрана Настройки"
                },
            )
        }

        if (uiState.loadState == SettingsLoadState.LOADING) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.semantics {
                            contentDescription = "Загрузка настроек"
                        },
                    )
                }
            }
        } else if (uiState.loadState == SettingsLoadState.ERROR) {
            item {
                SettingsCard(title = "Настройки временно недоступны") {
                    Text(
                        text = uiState.loadErrorMessage ?: "Повторите попытку позже.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = { onAction(SettingsAction.RetryLoad) },
                        modifier = Modifier.semantics {
                            contentDescription = "Повторить загрузку настроек"
                        },
                    ) {
                        Text("Повторить")
                    }
                }
            }
        } else {
            item {
                val systemDarkTheme = isSystemInDarkTheme()
                SettingsCard(title = "Оформление") {
                    DarkThemeRow(
                        darkTheme = uiState.themePreference
                            ?.let { it == ThemePreference.DARK }
                            ?: systemDarkTheme,
                        onDarkThemeChange = { dark ->
                            onAction(
                                SettingsAction.ThemeSelected(
                                    if (dark) ThemePreference.DARK else ThemePreference.LIGHT,
                                ),
                            )
                        },
                    )
                }
            }

            item {
                SettingsCard(title = "Устройство") {
                    SettingsTextField(
                        title = "Имя телефона",
                        value = uiState.deviceNameInput,
                        onValueChange = { onAction(SettingsAction.DeviceNameChanged(it)) },
                        fieldState = uiState.deviceNameState,
                        fieldDescription = "Поле имени телефона",
                        saveDescription = "Сохранить имя телефона",
                        isSaved = uiState.deviceNameState.errorMessage == null &&
                            uiState.deviceNameInput.trim() == uiState.settings.deviceName,
                        onSave = { onAction(SettingsAction.SaveDeviceName) },
                    )
                    SettingsTextField(
                        title = "Имя в сети",
                        supportingText = "Адрес на компьютере: http://" +
                            uiState.settings.networkName.value + ".local. Латинские буквы, цифры и дефис.",
                        value = uiState.networkNameInput,
                        onValueChange = { onAction(SettingsAction.NetworkNameChanged(it)) },
                        fieldState = uiState.networkNameState,
                        fieldDescription = "Поле имени в сети",
                        saveDescription = "Сохранить имя в сети",
                        isSaved = uiState.networkNameState.errorMessage == null &&
                            uiState.networkNameInput.trim().lowercase() == uiState.settings.networkName.value,
                        onSave = { onAction(SettingsAction.SaveNetworkName) },
                        suffix = ".local",
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Uri,
                        ),
                    )
                    if (uiState.networkNameAppliesAfterRestart) {
                        Text(
                            text = "Новый адрес заработает после перезапуска сервера.",
                            modifier = Modifier.testTag("network-name-restart-hint"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                SettingsCard(title = "Сервер") {
                    IdleStopSelector(
                        selected = uiState.settings.idleStopTimeout,
                        choices = IdleStopTimeout.USER_CHOICES,
                        fieldState = uiState.idleStopState,
                        onSelect = { onAction(SettingsAction.IdleStopSelected(it)) },
                    )
                    AddServerTileRow()
                    EventNotificationsRow()
                }
            }

            item {
                SettingsCard(title = "История") {
                    SettingsTextField(
                        title = "Срок хранения истории",
                        supportingText = "От 1 до 365 дней.",
                        value = uiState.retentionInput,
                        onValueChange = { onAction(SettingsAction.RetentionChanged(it)) },
                        fieldState = uiState.retentionState,
                        fieldDescription = "Поле срока хранения истории",
                        saveDescription = "Сохранить срок хранения истории",
                        isSaved = uiState.retentionState.errorMessage == null &&
                            uiState.retentionInput.toIntOrNull() == uiState.settings.retentionDays,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        onSave = { onAction(SettingsAction.SaveRetention) },
                    )
                }
            }

            item {
                SettingsCard(title = "Передача файлов") {
                    SettingsTextField(
                        title = "Максимальный размер файла",
                        supportingText = "Размер в МиБ, от 1 до 1024.",
                        value = uiState.fileLimitMiBInput,
                        onValueChange = { onAction(SettingsAction.FileLimitMiBChanged(it)) },
                        fieldState = uiState.fileLimitState,
                        fieldDescription = "Поле максимального размера файла",
                        saveDescription = "Сохранить максимальный размер файла",
                        isSaved = uiState.fileLimitState.errorMessage == null &&
                            mebibytesInputToBytes(uiState.fileLimitMiBInput) ==
                            uiState.settings.effectiveFileLimitBytes,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        onSave = { onAction(SettingsAction.SaveFileLimit) },
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Папка для входящих файлов",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        uiState.destinationState.errorMessage?.let { message ->
                            FieldError(message)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            TonalActionButton(
                                label = when (uiState.destinationAvailability) {
                                    DestinationAvailability.NONE -> "Выбрать"
                                    DestinationAvailability.UNAVAILABLE -> "Выбрать снова"
                                    else -> "Изменить"
                                },
                                onClick = { onAction(SettingsAction.ChooseDestination) },
                                enabled = !uiState.destinationState.isSaving,
                                modifier = Modifier.weight(1f),
                                contentDescription = "Выбрать папку для входящих файлов",
                            )
                            if (uiState.settings.destinationTree != null) {
                                OutlinedButton(
                                    onClick = { onAction(SettingsAction.ClearDestination) },
                                    enabled = !uiState.destinationState.isSaving,
                                    modifier = Modifier.semantics {
                                        contentDescription = "Сбросить папку для входящих файлов"
                                    },
                                ) {
                                    Text("Сбросить")
                                }
                            }
                        }
                    }

                    AutoAcceptRow(
                        checked = uiState.settings.autoAcceptTrustedFiles,
                        status = uiState.autoAcceptStatus,
                        fieldState = uiState.autoAcceptState,
                        onCheckedChange = { onAction(SettingsAction.AutoAcceptToggled(it)) },
                    )

                    PartialUploadsRow(
                        summary = uiState.partialUploads,
                        pending = uiState.discardPartialUploadsPending,
                        error = uiState.partialUploadsError,
                        onDiscard = { onAction(SettingsAction.DiscardPartialUploads) },
                    )
                }
            }

            item {
                SettingsCard(title = "Защищённое соединение") {
                    SecureModeSection(uiState = uiState, onAction = onAction)
                }
            }

            item {
                SettingsCard(title = "Доверенные браузеры", showDivider = false) {
                    if (uiState.trustedBrowsers.isEmpty()) {
                        Text(
                            text = "Доверенных браузеров пока нет",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "После явного разрешения здесь можно будет отозвать доступ отдельного браузера или всех сразу.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        Text(
                            text = "Эти браузеры могут восстановить подключение без нового кода в течение срока действия.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        uiState.trustedBrowsers.forEachIndexed { index, browser ->
                            if (index > 0) HorizontalDivider()
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = browser.browserLabel,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = "Последнее использование: ${formatTrustedTimestamp(browser.lastUsedAtEpochMillis)}",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    text = "Доступ действует до: ${formatTrustedTimestamp(browser.expiresAtEpochMillis)}",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                DestructiveActionButton(
                                    label = if (browser.id in uiState.revokingTrustedBrowserIds) {
                                        "Отзыв доступа…"
                                    } else {
                                        "Отозвать доступ"
                                    },
                                    onClick = {
                                        onAction(SettingsAction.RevokeTrustedBrowser(browser.id))
                                    },
                                    enabled =
                                        browser.id !in uiState.revokingTrustedBrowserIds &&
                                            !uiState.revokeAllTrustedBrowsersPending,
                                    contentDescription =
                                        "Отозвать доступ ${browser.browserLabel}",
                                )
                            }
                        }
                        uiState.trustedBrowsersError?.let { FieldError(it) }
                        DestructiveActionButton(
                            label = if (uiState.revokeAllTrustedBrowsersPending) {
                                "Отзыв доступа…"
                            } else {
                                "Отозвать доступ всех"
                            },
                            onClick = { onAction(SettingsAction.RevokeAllTrustedBrowsers) },
                            enabled = !uiState.revokeAllTrustedBrowsersPending &&
                                uiState.revokingTrustedBrowserIds.isEmpty(),
                            contentDescription = "Отозвать доступ всех доверенных браузеров",
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    showDivider: Boolean = true,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SectionHeader(title = title)
        content()
        if (showDivider) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

internal fun formatTrustedTimestamp(epochMillis: Long?): String =
    epochMillis?.let(::formatDayAndMinute) ?: "ещё не использовался"
