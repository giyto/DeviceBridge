package ru.hznik.devicebridge.di

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import dagger.Module
import dagger.hilt.EntryPoint
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import ru.hznik.devicebridge.data.persistence.datastore.DataStoreSettingsRepository
import ru.hznik.devicebridge.data.persistence.datastore.DataStoreThemePreferenceRepository
import ru.hznik.devicebridge.data.persistence.room.DEVICE_BRIDGE_MIGRATIONS
import ru.hznik.devicebridge.data.persistence.room.DeviceBridgeDatabase
import ru.hznik.devicebridge.data.persistence.room.HistoryDao
import ru.hznik.devicebridge.data.persistence.room.RoomHistoryRepository
import ru.hznik.devicebridge.data.persistence.room.TrustedBrowserDao
import ru.hznik.devicebridge.data.history.TextTransferHistoryRecorder
import ru.hznik.devicebridge.data.history.HistoryPersistenceEventBus
import ru.hznik.devicebridge.data.history.HistoryPersistenceFailureReporter
import ru.hznik.devicebridge.data.text.TextTerminalHistoryRecorder
import ru.hznik.devicebridge.domain.repository.HistoryRepository
import ru.hznik.devicebridge.domain.repository.SettingsRepository
import ru.hznik.devicebridge.domain.repository.ThemePreferenceRepository
import ru.hznik.devicebridge.domain.repository.TrustedBrowserRepository
import ru.hznik.devicebridge.domain.settings.androidDeviceName
import ru.hznik.devicebridge.data.trust.AndroidKeystoreTrustedHmacKeyProvider
import ru.hznik.devicebridge.data.trust.HmacSha256TrustedCredentialVerifier
import ru.hznik.devicebridge.data.trust.RoomTrustedBrowserRepository
import ru.hznik.devicebridge.data.trust.SecureRandomTrustedCredentialGenerator
import ru.hznik.devicebridge.data.trust.TrustedCredentialGenerator
import ru.hznik.devicebridge.data.trust.TrustedCredentialVerifier
import ru.hznik.devicebridge.data.trust.TrustedHmacKeyProvider
import ru.hznik.devicebridge.domain.usecase.ClearHistoryUseCase
import ru.hznik.devicebridge.domain.usecase.DeleteHistoryRecordUseCase
import ru.hznik.devicebridge.domain.usecase.ObserveHistoryUseCase
import ru.hznik.devicebridge.domain.usecase.ObserveSettingsUseCase
import ru.hznik.devicebridge.domain.usecase.UpdateDestinationTreeUseCase
import ru.hznik.devicebridge.domain.usecase.UpdateDeviceNameUseCase
import ru.hznik.devicebridge.domain.usecase.UpdateFileLimitUseCase
import ru.hznik.devicebridge.domain.usecase.UpdateAutoAcceptTrustedFilesUseCase
import ru.hznik.devicebridge.domain.usecase.UpdateIdleStopTimeoutUseCase
import ru.hznik.devicebridge.domain.usecase.UpdateRetentionDaysUseCase

private const val DATABASE_NAME = "devicebridge.db"
private const val SETTINGS_FILE_NAME = "devicebridge_settings"

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PhysicalDeviceName

@Module
@InstallIn(SingletonComponent::class)
object PersistenceModule {
    @Provides
    @Singleton
    fun provideDeviceBridgeDatabase(
        @ApplicationContext context: Context,
    ): DeviceBridgeDatabase = Room.databaseBuilder(
        context,
        DeviceBridgeDatabase::class.java,
        DATABASE_NAME,
    ).addMigrations(*DEVICE_BRIDGE_MIGRATIONS).build()

    @Provides
    @Singleton
    fun provideHistoryDao(database: DeviceBridgeDatabase): HistoryDao =
        database.historyDao()

    @Provides
    @Singleton
    fun providePartialUploadDao(
        database: DeviceBridgeDatabase,
    ): ru.hznik.devicebridge.data.persistence.room.PartialUploadDao = database.partialUploadDao()

    @Provides
    @Singleton
    fun providePartialUploadStore(
        dao: ru.hznik.devicebridge.data.persistence.room.PartialUploadDao,
        @ApplicationContext context: Context,
    ): ru.hznik.devicebridge.data.file.PartialUploadStore =
        ru.hznik.devicebridge.data.file.RoomPartialUploadStore(
            dao = dao,
            documents = ru.hznik.devicebridge.data.file.ContentResolverPartialDocumentProvider(
                context.contentResolver,
            ),
        )

    @Provides
    @Singleton
    fun provideTrustedBrowserDao(database: DeviceBridgeDatabase): TrustedBrowserDao =
        database.trustedBrowserDao()

    @Provides
    @Singleton
    fun provideSettingsDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        produceFile = { context.preferencesDataStoreFile(SETTINGS_FILE_NAME) },
    )

    @Provides
    @PhysicalDeviceName
    fun providePhysicalDeviceName(): String =
        androidDeviceName(Build.MANUFACTURER, Build.MODEL)

    @Provides
    @Singleton
    fun provideSettingsRepository(
        @ApplicationContext context: Context,
        dataStore: DataStore<Preferences>,
        @PhysicalDeviceName defaultDeviceName: String,
    ): SettingsRepository = DataStoreSettingsRepository(
        dataStore = dataStore,
        defaultDeviceName = defaultDeviceName,
        // The one-minute idle stop exists only so E2E checks on debug builds do not wait.
        allowDebugIdleTimeout =
            context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
    )

    @Provides
    @Singleton
    fun provideThemePreferenceRepository(
        dataStore: DataStore<Preferences>,
    ): ThemePreferenceRepository = DataStoreThemePreferenceRepository(dataStore)

    @Provides
    @Singleton
    fun provideUtcClock(): Clock = Clock.systemUTC()

    @Provides
    @Singleton
    fun provideHistoryRepository(
        dao: HistoryDao,
        settingsRepository: SettingsRepository,
        clock: Clock,
        @ApplicationScope applicationScope: CoroutineScope,
    ): HistoryRepository = RoomHistoryRepository(
        dao = dao,
        settingsRepository = settingsRepository,
        clock = clock,
        applicationScope = applicationScope,
    )

    @Provides
    @Singleton
    fun provideTrustedCredentialGenerator(
        implementation: SecureRandomTrustedCredentialGenerator,
    ): TrustedCredentialGenerator = implementation

    @Provides
    @Singleton
    fun provideTrustedHmacKeyProvider(
        implementation: AndroidKeystoreTrustedHmacKeyProvider,
    ): TrustedHmacKeyProvider = implementation

    @Provides
    @Singleton
    fun provideTrustedCredentialVerifier(
        implementation: HmacSha256TrustedCredentialVerifier,
    ): TrustedCredentialVerifier = implementation

    @Provides
    @Singleton
    fun provideTrustedBrowserRepository(
        dao: TrustedBrowserDao,
        credentialGenerator: TrustedCredentialGenerator,
        credentialVerifier: TrustedCredentialVerifier,
        clock: Clock,
        @ApplicationScope applicationScope: CoroutineScope,
    ): TrustedBrowserRepository = RoomTrustedBrowserRepository(
        dao = dao,
        credentialGenerator = credentialGenerator,
        credentialVerifier = credentialVerifier,
        clock = clock,
        applicationScope = applicationScope,
    )

    @Provides
    @Singleton
    fun provideTextTerminalHistoryRecorder(
        historyRepository: HistoryRepository,
        @ApplicationScope applicationScope: CoroutineScope,
        failureReporter: HistoryPersistenceFailureReporter,
    ): TextTerminalHistoryRecorder = TextTransferHistoryRecorder(
        repository = historyRepository,
        applicationScope = applicationScope,
        failureReporter = failureReporter,
    )

    @Provides
    @Singleton
    fun provideHistoryPersistenceEventBus(): HistoryPersistenceEventBus =
        HistoryPersistenceEventBus()

    @Provides
    @Singleton
    fun provideHistoryPersistenceFailureReporter(
        eventBus: HistoryPersistenceEventBus,
    ): HistoryPersistenceFailureReporter = eventBus

    @Provides
    fun provideObserveHistoryUseCase(
        repository: HistoryRepository,
    ): ObserveHistoryUseCase = ObserveHistoryUseCase(repository)

    @Provides
    fun provideDeleteHistoryRecordUseCase(
        repository: HistoryRepository,
    ): DeleteHistoryRecordUseCase = DeleteHistoryRecordUseCase(repository)

    @Provides
    fun provideClearHistoryUseCase(
        repository: HistoryRepository,
    ): ClearHistoryUseCase = ClearHistoryUseCase(repository)

    @Provides
    fun provideObserveSettingsUseCase(
        repository: SettingsRepository,
    ): ObserveSettingsUseCase = ObserveSettingsUseCase(repository)

    @Provides
    fun provideUpdateDeviceNameUseCase(
        repository: SettingsRepository,
    ): UpdateDeviceNameUseCase = UpdateDeviceNameUseCase(repository)

    @Provides
    fun provideUpdateRetentionDaysUseCase(
        repository: SettingsRepository,
    ): UpdateRetentionDaysUseCase = UpdateRetentionDaysUseCase(repository)

    @Provides
    fun provideUpdateDestinationTreeUseCase(
        repository: SettingsRepository,
    ): UpdateDestinationTreeUseCase = UpdateDestinationTreeUseCase(repository)

    @Provides
    fun provideUpdateFileLimitUseCase(
        repository: SettingsRepository,
    ): UpdateFileLimitUseCase = UpdateFileLimitUseCase(repository)

    @Provides
    fun provideUpdateAutoAcceptTrustedFilesUseCase(
        repository: SettingsRepository,
    ): UpdateAutoAcceptTrustedFilesUseCase = UpdateAutoAcceptTrustedFilesUseCase(repository)

    @Provides
    fun provideUpdateIdleStopTimeoutUseCase(
        repository: SettingsRepository,
    ): UpdateIdleStopTimeoutUseCase = UpdateIdleStopTimeoutUseCase(repository)
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface PersistenceEntryPoint {
    fun database(): DeviceBridgeDatabase
    fun settingsRepository(): SettingsRepository
    fun trustedBrowserRepository(): TrustedBrowserRepository
}
