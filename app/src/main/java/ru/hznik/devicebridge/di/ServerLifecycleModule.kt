package ru.hznik.devicebridge.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import ru.hznik.devicebridge.data.server.AndroidMonotonicClock
import ru.hznik.devicebridge.data.server.AndroidServerLifecycleRepository
import ru.hznik.devicebridge.data.server.AndroidServerSessionJournal
import ru.hznik.devicebridge.data.server.AndroidServerServiceCommandGateway
import ru.hznik.devicebridge.data.server.MonotonicClock
import ru.hznik.devicebridge.data.server.ServerLifecycleCoordinator
import ru.hznik.devicebridge.data.server.ServerServiceCommandGateway
import ru.hznik.devicebridge.data.server.ServerSessionJournal
import ru.hznik.devicebridge.data.server.StopTimeoutPolicy
import ru.hznik.devicebridge.domain.repository.ServerLifecycleRepository
import ru.hznik.devicebridge.feature.home.DefaultHomeUptimeTicker
import ru.hznik.devicebridge.feature.home.HomeUptimeTicker
import ru.hznik.devicebridge.server.AndroidServerNotificationController
import ru.hznik.devicebridge.server.ServerNotificationController
import ru.hznik.devicebridge.data.session.BrowserSessionCoordinator
import ru.hznik.devicebridge.data.session.SessionEventDispatcher
import ru.hznik.devicebridge.data.session.security.JavaCryptographicRandom
import ru.hznik.devicebridge.data.session.security.SessionSecretGenerator
import ru.hznik.devicebridge.domain.repository.BrowserSessionRepository
import ru.hznik.devicebridge.domain.repository.TextTransferRepository
import ru.hznik.devicebridge.data.text.TextTransferCoordinator
import ru.hznik.devicebridge.domain.repository.FileTransferRepository
import ru.hznik.devicebridge.data.file.FileTransferCoordinator
import ru.hznik.devicebridge.data.file.FileUploadTargetFactory
import ru.hznik.devicebridge.data.file.AndroidFileUploadTargetFactory
import ru.hznik.devicebridge.data.file.FileDownloadSourceFactory
import ru.hznik.devicebridge.data.file.AndroidFileDownloadSourceFactory
import ru.hznik.devicebridge.data.file.AndroidFileTransferWifiLock
import ru.hznik.devicebridge.data.file.FileTransferWifiLock
import ru.hznik.devicebridge.data.file.FileDestinationLeaseRegistry
import ru.hznik.devicebridge.data.file.FileSourceRegistry
import ru.hznik.devicebridge.web.FileSessionEventBridge
import kotlinx.coroutines.CoroutineScope
import ru.hznik.devicebridge.domain.usecase.ObserveTextTransfersUseCase
import ru.hznik.devicebridge.domain.usecase.ReceiveTextFromBrowserUseCase
import ru.hznik.devicebridge.domain.usecase.RetryTextTransferUseCase
import ru.hznik.devicebridge.domain.usecase.SendTextToBrowserUseCase

@Module
@InstallIn(SingletonComponent::class)
abstract class ServerLifecycleModule {

    @Binds
    @Singleton
    abstract fun bindMonotonicClock(
        implementation: AndroidMonotonicClock,
    ): MonotonicClock

    @Binds
    @Singleton
    abstract fun bindServerServiceCommandGateway(
        implementation: AndroidServerServiceCommandGateway,
    ): ServerServiceCommandGateway

    @Binds
    @Singleton
    abstract fun bindServerNotificationController(
        implementation: AndroidServerNotificationController,
    ): ServerNotificationController

    @Binds
    @Singleton
    abstract fun bindServerSessionJournal(
        implementation: AndroidServerSessionJournal,
    ): ServerSessionJournal

    @Binds
    abstract fun bindHomeUptimeTicker(
        implementation: DefaultHomeUptimeTicker,
    ): HomeUptimeTicker

    @Binds
    @Singleton
    abstract fun bindFileTransferWifiLock(
        implementation: AndroidFileTransferWifiLock,
    ): FileTransferWifiLock

    companion object {
        @Provides
        @Singleton
        fun provideStopTimeoutPolicy(): StopTimeoutPolicy = StopTimeoutPolicy()

        @Provides
        @Singleton
        fun provideBrowserSessionCoordinator(
            monotonicClock: MonotonicClock,
            @ApplicationScope applicationScope: CoroutineScope,
        ): BrowserSessionCoordinator = BrowserSessionCoordinator(
            clock = monotonicClock,
            secretGenerator = SessionSecretGenerator(JavaCryptographicRandom()),
            scope = applicationScope,
        )

        @Provides
        @Singleton
        fun provideBrowserSessionRepository(
            coordinator: BrowserSessionCoordinator,
        ): BrowserSessionRepository = coordinator

        @Provides
        @Singleton
        fun provideSessionEventDispatcher(
            @ApplicationScope applicationScope: CoroutineScope,
        ): SessionEventDispatcher = SessionEventDispatcher(scope = applicationScope)

        @Provides
        @Singleton
        fun provideTextTransferCoordinator(
            browserSessions: BrowserSessionCoordinator,
            eventDispatcher: SessionEventDispatcher,
        ): TextTransferCoordinator = TextTransferCoordinator(
            nowEpochMillis = System::currentTimeMillis,
            browserSessionState = { browserSessions.state.value },
            eventGateway = eventDispatcher,
        )

        @Provides
        @Singleton
        fun provideTextTransferRepository(
            coordinator: TextTransferCoordinator,
        ): TextTransferRepository = coordinator

        @Provides
        @Singleton
        fun provideFileTransferCoordinator(
            browserSessions: BrowserSessionCoordinator,
            wifiLock: FileTransferWifiLock,
        ): FileTransferCoordinator = FileTransferCoordinator(
            browserSessionState = { browserSessions.state.value },
            wifiLock = wifiLock,
        )

        @Provides
        @Singleton
        fun provideFileTransferRepository(
            coordinator: FileTransferCoordinator,
        ): FileTransferRepository = coordinator

        @Provides
        @Singleton
        fun provideFileUploadTargetFactory(
            factory: AndroidFileUploadTargetFactory,
        ): FileUploadTargetFactory = factory

        @Provides
        @Singleton
        fun provideFileDownloadSourceFactory(
            factory: AndroidFileDownloadSourceFactory,
        ): FileDownloadSourceFactory = factory

        @Provides
        @Singleton
        fun provideFileSessionEventBridge(
            @ApplicationScope applicationScope: CoroutineScope,
            coordinator: FileTransferCoordinator,
            dispatcher: SessionEventDispatcher,
            browserSessions: BrowserSessionCoordinator,
            destinationLeases: FileDestinationLeaseRegistry,
            fileSourceRegistry: FileSourceRegistry,
        ): FileSessionEventBridge = FileSessionEventBridge(
            scope = applicationScope,
            coordinator = coordinator,
            dispatcher = dispatcher,
            wallClockMs = System::currentTimeMillis,
            browserSessionState = browserSessions.state,
            destinationLeases = destinationLeases,
            sourceRegistry = fileSourceRegistry,
        )

        @Provides
        fun provideObserveFileTransfersUseCase(repository: FileTransferRepository) =
            ru.hznik.devicebridge.domain.usecase.ObserveFileTransfersUseCase(repository)

        @Provides
        fun provideCreateFileTransfersUseCase(repository: FileTransferRepository) =
            ru.hznik.devicebridge.domain.usecase.CreateFileTransfersUseCase(repository)

        @Provides
        fun provideApproveFileTransferUseCase(repository: FileTransferRepository) =
            ru.hznik.devicebridge.domain.usecase.ApproveFileTransferUseCase(repository)

        @Provides
        fun provideCancelFileTransferUseCase(repository: FileTransferRepository) =
            ru.hznik.devicebridge.domain.usecase.CancelFileTransferUseCase(repository)

        @Provides
        fun provideRetryFileTransferUseCase(repository: FileTransferRepository) =
            ru.hznik.devicebridge.domain.usecase.RetryFileTransferUseCase(repository)

        @Provides
        fun provideVerifyFileTransferUseCase(repository: FileTransferRepository) =
            ru.hznik.devicebridge.domain.usecase.VerifyFileTransferUseCase(repository)

        @Provides
        fun provideObserveTextTransfersUseCase(
            repository: TextTransferRepository,
        ): ObserveTextTransfersUseCase = ObserveTextTransfersUseCase(repository)

        @Provides
        fun provideSendTextToBrowserUseCase(
            repository: TextTransferRepository,
        ): SendTextToBrowserUseCase = SendTextToBrowserUseCase(repository)

        @Provides
        fun provideReceiveTextFromBrowserUseCase(
            repository: TextTransferRepository,
        ): ReceiveTextFromBrowserUseCase = ReceiveTextFromBrowserUseCase(repository)

        @Provides
        fun provideRetryTextTransferUseCase(
            repository: TextTransferRepository,
        ): RetryTextTransferUseCase = RetryTextTransferUseCase(repository)

        @Provides
        @Singleton
        fun provideServerLifecycleRepository(
            coordinator: ServerLifecycleCoordinator,
            serviceCommands: ServerServiceCommandGateway,
        ): ServerLifecycleRepository = AndroidServerLifecycleRepository(
            state = coordinator.state,
            serviceCommands = serviceCommands,
        )

        @Provides
        fun provideStartServerUseCase(
            repository: ServerLifecycleRepository,
        ) = ru.hznik.devicebridge.domain.usecase.StartServerUseCase(repository)

        @Provides
        fun provideStopServerUseCase(
            repository: ServerLifecycleRepository,
        ) = ru.hznik.devicebridge.domain.usecase.StopServerUseCase(repository)

        @Provides
        fun provideObserveServerLifecycleUseCase(
            repository: ServerLifecycleRepository,
        ) = ru.hznik.devicebridge.domain.usecase.ObserveServerLifecycleUseCase(repository)

        @Provides
        fun provideObserveBrowserSessionsUseCase(repository: BrowserSessionRepository) =
            ru.hznik.devicebridge.domain.usecase.ObserveBrowserSessionsUseCase(repository)

        @Provides
        fun provideApproveBrowserRequestUseCase(repository: BrowserSessionRepository) =
            ru.hznik.devicebridge.domain.usecase.ApproveBrowserRequestUseCase(repository)

        @Provides
        fun provideDenyBrowserRequestUseCase(repository: BrowserSessionRepository) =
            ru.hznik.devicebridge.domain.usecase.DenyBrowserRequestUseCase(repository)

        @Provides
        fun provideRevokeBrowserSessionUseCase(repository: BrowserSessionRepository) =
            ru.hznik.devicebridge.domain.usecase.RevokeBrowserSessionUseCase(repository)
    }
}
