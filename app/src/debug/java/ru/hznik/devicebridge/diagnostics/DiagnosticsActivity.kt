package ru.hznik.devicebridge.diagnostics

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import ru.hznik.devicebridge.diagnostics.server.KtorCioRuntimeFactory
import ru.hznik.devicebridge.diagnostics.server.ManagedEmbeddedServerController
import ru.hznik.devicebridge.diagnostics.server.ServerState
import ru.hznik.devicebridge.ui.theme.DeviceBridgeTheme

class DiagnosticsActivity : ComponentActivity() {
    private val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val controller = ManagedEmbeddedServerController(
        runtimeFactory = KtorCioRuntimeFactory(
            sdkIntProvider = { Build.VERSION.SDK_INT },
        ),
    )
    private val permissionError = mutableStateOf<String?>(null)

    private val localNetworkPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            permissionError.value = null
            startServer()
        } else {
            permissionError.value =
                "Доступ к локальной сети запрещён. Разрешите его и повторите запуск."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DeviceBridgeTheme {
                val state by controller.state.collectAsStateWithLifecycle()
                DiagnosticsScreen(
                    uiModel = toDiagnosticsUiModel(state),
                    permissionError = permissionError.value,
                    onPrimaryAction = {
                        if (state is ServerState.Running) {
                            stopServer()
                        } else {
                            requestPermissionAndStart()
                        }
                    },
                    onRestart = {
                        serverScope.launch {
                            controller.stop()
                            controller.start(DIAGNOSTIC_PORT)
                        }
                    },
                )
            }
        }
    }

    override fun onDestroy() {
        runBlocking {
            controller.stop()
        }
        serverScope.cancel()
        super.onDestroy()
    }

    private fun requestPermissionAndStart() {
        val needsPermission = Build.VERSION.SDK_INT >= 37 &&
            ContextCompat.checkSelfPermission(
                this,
                ACCESS_LOCAL_NETWORK_PERMISSION,
            ) != PackageManager.PERMISSION_GRANTED

        if (needsPermission) {
            localNetworkPermissionLauncher.launch(ACCESS_LOCAL_NETWORK_PERMISSION)
        } else {
            permissionError.value = null
            startServer()
        }
    }

    private fun startServer() {
        serverScope.launch {
            controller.start(DIAGNOSTIC_PORT)
        }
    }

    private fun stopServer() {
        serverScope.launch {
            controller.stop()
        }
    }

    private companion object {
        const val ACCESS_LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
        const val DIAGNOSTIC_PORT = 8_787
    }
}

@Composable
fun DiagnosticsScreen(
    uiModel: DiagnosticsUiModel,
    permissionError: String?,
    onPrimaryAction: () -> Unit,
    onRestart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(PaddingValues(horizontal = 20.dp, vertical = 28.dp)),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Server Lab",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Изолированная проверка Ktor 3.5.2 / CIO на Android.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            DiagnosticsStatusCard(uiModel)

            permissionError?.let { message ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Text(
                        text = message,
                        modifier = Modifier.padding(18.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            uiModel.address?.let { address ->
                DiagnosticValueCard(
                    label = "Адрес сервера",
                    value = address,
                )
            }

            uiModel.token?.let { token ->
                DiagnosticValueCard(
                    label = "Bearer token",
                    value = token,
                )
            }

            Button(
                onClick = onPrimaryAction,
                enabled = uiModel.primaryActionEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = uiModel.primaryActionLabel
                    },
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            ) {
                if (uiModel.isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Text(uiModel.primaryActionLabel)
            }

            if (uiModel.isRunning) {
                OutlinedButton(
                    onClick = onRestart,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                ) {
                    Text("Перезапустить")
                }
            }

            Text(
                text = "Маршруты /diagnostics/* работают только в debug-сборке. " +
                    "Внешний backend не используется.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DiagnosticsStatusCard(uiModel: DiagnosticsUiModel) {
    val indicatorColor: Color = when {
        uiModel.errorMessage != null -> MaterialTheme.colorScheme.error
        uiModel.isRunning -> Color(0xFF43A46C)
        uiModel.isBusy -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = "Состояние сервера: " + uiModel.statusLabel
            },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(indicatorColor),
            )
            Spacer(Modifier.width(14.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = uiModel.statusLabel,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = uiModel.supportingText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                uiModel.errorMessage?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun DiagnosticValueCard(
    label: String,
    value: String,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SelectionContainer {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}
