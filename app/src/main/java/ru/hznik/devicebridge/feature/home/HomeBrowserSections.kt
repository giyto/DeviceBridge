package ru.hznik.devicebridge.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun PendingBrowsersSection(
    requests: List<PendingBrowserUiState>,
    onAction: (HomeAction) -> Unit,
) {
    // A new request is the one thing to do here, including after "Открыть" in its notification:
    // it sits below the address and code, so it is scrolled into view.
    val bringIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(requests.lastOrNull()?.id) {
        bringIntoView.bringIntoView()
    }
    Column(
        modifier = Modifier.bringIntoViewRequester(bringIntoView),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Запросы на подключение",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        requests.forEach { request ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = request.browserLabel,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.semantics {
                            contentDescription =
                                "Новый запрос на подключение: ${request.browserLabel}, ${request.sourceIpv4}"
                            liveRegion = LiveRegionMode.Polite
                        },
                    )
                    Text(
                        text = request.sourceIpv4,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        text = "Запрос истечёт через ${formatCountdown(request.expiresInSeconds)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    if (request.rememberBrowserRequested) {
                        Text(
                            text = "Браузер просит сохранить доступ на этом устройстве на срок до 30 дней.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                    HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedButton(
                            onClick = {
                                onAction(HomeAction.DenyBrowser(request.id))
                            },
                            enabled = !request.actionPending,
                            modifier = Modifier
                                .weight(1f)
                                .semantics {
                                    contentDescription =
                                        "Отклонить ${request.browserLabel} с адреса ${request.sourceIpv4}"
                                },
                        ) { Text("Отклонить") }
                        Button(
                            onClick = {
                                onAction(HomeAction.ApproveBrowser(request.id))
                            },
                            enabled = !request.actionPending,
                            modifier = Modifier
                                .weight(1f)
                                .semantics {
                                    contentDescription =
                                        "Разрешить ${request.browserLabel} с адреса ${request.sourceIpv4}"
                                },
                        ) {
                            Text(if (request.rememberBrowserRequested) "Один раз" else "Разрешить")
                        }
                    }
                    if (request.rememberBrowserRequested) {
                        Button(
                            onClick = {
                                onAction(HomeAction.ApproveAndRememberBrowser(request.id))
                            },
                            enabled = !request.actionPending,
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics {
                                    contentDescription =
                                        "Разрешить и запомнить ${request.browserLabel} с адреса ${request.sourceIpv4}"
                                },
                        ) { Text("Разрешить и запомнить") }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ActiveBrowsersSection(
    sessions: List<ActiveBrowserUiState>,
    connectedCount: Int,
    onAction: (HomeAction) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Подключённые браузеры: $connectedCount",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        sessions.forEach { session ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Column(
                        modifier = if (session.connected) {
                            Modifier
                        } else {
                            Modifier.semantics(mergeDescendants = true) {
                                contentDescription =
                                    "${session.browserLabel}, ${session.sourceIpv4}, не в сети"
                            }
                        },
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(session.browserLabel, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = session.sourceIpv4,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (!session.connected) {
                            Text(
                                text = "Не в сети",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            onAction(HomeAction.RevokeBrowser(session.id))
                        },
                        enabled = !session.actionPending,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                contentDescription =
                                    "Отключить ${session.browserLabel} с адреса ${session.sourceIpv4}" +
                                    if (session.connected) "" else ", не в сети"
                            },
                    ) { Text("Отключить") }
                }
            }
        }
    }
}
