package ru.hznik.devicebridge.feature.home

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ru.hznik.devicebridge.core.ui.ConnectionStatusCard
import ru.hznik.devicebridge.core.ui.QuickActionCard
import ru.hznik.devicebridge.ui.theme.DeviceBridgeTheme

@Composable
fun HomeScreen(
    uiState: ServerSessionUiState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(PaddingValues(horizontal = 20.dp, vertical = 28.dp)),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "DeviceBridge",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Передача данных между телефоном и браузером в вашей локальной сети.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        ConnectionStatusCard(
            statusLabel = "Сервер остановлен",
            supportingText = "Телефон пока не принимает подключения от компьютера.",
        )

        Button(
            onClick = {},
            enabled = false,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Text("Запустить сервер")
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "Быстрые действия",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            QuickActionCard(
                symbol = "Aa",
                title = "Текст",
                supportingText = "Отправить заметку или ссылку",
                enabled = uiState.canSendText,
                onClick = {},
            )
            QuickActionCard(
                symbol = "⇧",
                title = "Файлы",
                supportingText = "Передать документ или изображение",
                enabled = uiState.canSendFiles,
                onClick = {},
            )
        }

        Text(
            text = "Сначала запустите сервер, затем подключите браузер.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Preview(name = "Главная — светлая", showBackground = true)
@Composable
private fun HomeScreenLightPreview() {
    DeviceBridgeTheme(darkTheme = false, dynamicColor = false) {
        HomeScreen(uiState = ServerSessionUiState())
    }
}

@Preview(
    name = "Главная — тёмная, шрифт 200%",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    fontScale = 2f,
)
@Composable
private fun HomeScreenDarkLargeFontPreview() {
    DeviceBridgeTheme(darkTheme = true, dynamicColor = false) {
        HomeScreen(uiState = ServerSessionUiState())
    }
}
