package ru.hznik.devicebridge.core.ui

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertTrue
import org.junit.Test

class DesignPrimitivesContractTest {
    @Test
    fun graphitePrimitivesCoverHeadersMetadataOperationsAndActionHierarchy() {
        val primitives = read("src/main/java/ru/hznik/devicebridge/core/ui/DesignPrimitives.kt")

        listOf(
            "fun ScreenHeader(",
            "fun SectionHeader(",
            "fun MetadataRow(",
            "fun OperationalItem(",
            "fun DestructiveActionButton(",
        ).forEach { api ->
            assertTrue("Missing reusable primitive: $api", primitives.contains(api))
        }
    }

    @Test
    fun sharedStatesCoverLoadingSuccessErrorAndCancellationWithoutColorOnlyMeaning() {
        val stateComponents = read("src/main/java/ru/hznik/devicebridge/core/ui/StateComponents.kt")

        assertTrue(stateComponents.contains("LOADING"))
        assertTrue(stateComponents.contains("CANCELLED"))
        assertTrue(stateComponents.contains("loading: Boolean = false"))
        assertTrue(stateComponents.contains("stateDescription"))
        assertTrue(stateComponents.contains("statusLabel"))
    }

    @Test
    fun signalFlowIsFiniteDeclarativeAndHasTextualStatus() {
        val primitives = read("src/main/java/ru/hznik/devicebridge/core/ui/DesignPrimitives.kt")

        assertTrue(primitives.contains("fun SignalFlowIndicator("))
        assertTrue(primitives.contains("animateFloatAsState("))
        assertTrue(primitives.contains("tween("))
        assertTrue(primitives.contains("contentDescription = statusLabel"))
        assertTrue(!primitives.contains("infiniteRepeatable("))
    }

    @Test
    fun adaptiveNavigationUsesTheSharedPaletteWithoutChangingDestinationIteration() {
        val app = read("src/main/java/ru/hznik/devicebridge/app/DeviceBridgeApp.kt")

        assertTrue(app.contains("NavigationBarItemDefaults.colors("))
        assertTrue(app.contains("NavigationRailItemDefaults.colors("))
        assertTrue(app.contains("containerColor = MaterialTheme.colorScheme.surface"))
        assertTrue(app.contains("tonalElevation = 0.dp"))
        assertTrue(app.contains("TopLevelDestination.entries.forEach"))
    }

    @Test
    fun homeDashboardUsesSharedHierarchyWithoutChangingItsActionBoundary() {
        val home = read("src/main/java/ru/hznik/devicebridge/feature/home/HomeScreen.kt")

        listOf(
            "ScreenHeader(",
            "SectionHeader(",
            "OperationalItem(",
            "MetadataRow(",
            "SignalFlowIndicator(",
        ).forEach { primitive ->
            assertTrue("Home is missing $primitive", home.contains(primitive))
        }
        assertTrue(home.contains("onAction: (HomeAction) -> Unit"))
    }

    @Test
    fun textScreenUsesSharedHeaderComposerActionAndOperationalFeed() {
        val screen = read("src/main/java/ru/hznik/devicebridge/feature/text/TextScreen.kt")

        listOf(
            "ScreenHeader(",
            "SectionHeader(",
            "OperationalItem(",
            "PrimaryActionButton(",
        ).forEach { primitive ->
            assertTrue("Text screen is missing $primitive", screen.contains(primitive))
        }
        assertTrue(screen.contains("onAction: (TextAction) -> Unit"))
        assertTrue(screen.contains("onValueChange = { onAction(TextAction.DraftChanged(it)) }"))
    }

    @Test
    fun fileScreenUsesSharedDraftActionAndOperationalQueueHierarchy() {
        val screen = read("src/main/java/ru/hznik/devicebridge/feature/file/FileScreen.kt")

        listOf(
            "ScreenHeader(",
            "SectionHeader(",
            "OperationalItem(",
            "PrimaryActionButton(",
            "SecondaryActionButton(",
        ).forEach { primitive ->
            assertTrue("File screen is missing $primitive", screen.contains(primitive))
        }
        assertTrue(screen.contains("onAction: (FileAction) -> Unit"))
        assertTrue(screen.contains("FileAction.RemoveDraftItem(item.id)"))
        assertTrue(screen.contains("FileAction.Cancel(item.id)"))
        assertTrue(screen.contains("FileAction.Retry(item.id)"))
    }

    @Test
    fun historyUsesDedicatedCardsAndFilterSheetWhileSettingsKeepsSharedSurfaces() {
        val history = read("src/main/java/ru/hznik/devicebridge/feature/history/HistoryScreen.kt")
        val settings = read("src/main/java/ru/hznik/devicebridge/feature/settings/SettingsScreen.kt")

        listOf(
            "ScreenHeader(",
            "HistoryFilterTrigger(",
            "HistoryFilterSheet(",
            "HistoryRecordCard(",
            "ModalBottomSheet(",
        ).forEach { primitive ->
            assertTrue("History is missing $primitive", history.contains(primitive))
        }
        assertTrue(!history.contains("OperationalItem("))
        assertTrue(!history.contains("FilterChip("))
        listOf(
            "ScreenHeader(",
            "SectionHeader(",
            "PrimaryActionButton(",
            "DestructiveActionButton(",
        ).forEach { primitive ->
            assertTrue("Settings is missing $primitive", settings.contains(primitive))
        }
        assertTrue(history.contains("HistoryAction.ToggleDirection(it)"))
        assertTrue(history.contains("HistoryAction.ResetFilters"))
        assertTrue(history.contains("HistoryAction.RetryLoad"))
        assertTrue(settings.contains("SettingsAction.DeviceNameChanged(it)"))
        assertTrue(settings.contains("SettingsAction.SaveDeviceName"))
    }

    private fun read(relativePath: String): String = Files.readString(Path.of(relativePath))
}
