package ru.hznik.devicebridge.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import javax.inject.Singleton
import ru.hznik.devicebridge.data.network.AndroidLanNetworkSnapshotProvider
import ru.hznik.devicebridge.data.network.AndroidNetworkCallbackRegistrar
import ru.hznik.devicebridge.data.network.DefaultLanNetworkObserver
import ru.hznik.devicebridge.data.network.LanNetworkObserver
import ru.hznik.devicebridge.data.network.LanNetworkSnapshotProvider
import ru.hznik.devicebridge.data.network.NetworkCallbackRegistrar
import ru.hznik.devicebridge.data.network.mdns.AndroidLocalNamePublisher
import ru.hznik.devicebridge.data.network.mdns.LocalNamePublisher
import ru.hznik.devicebridge.data.server.KtorServerRuntimeFactory
import ru.hznik.devicebridge.data.server.DEFAULT_PRODUCTION_SERVER_PORT
import ru.hznik.devicebridge.data.server.ProductionServerPort
import ru.hznik.devicebridge.data.server.ServerRuntimeFactory
import ru.hznik.devicebridge.web.AssetManagerWebAssetProvider
import ru.hznik.devicebridge.web.WebAssetProvider

@Module
@InstallIn(SingletonComponent::class)
abstract class ServerNetworkModule {

    @Binds
    @Singleton
    abstract fun bindLanNetworkSnapshotProvider(
        implementation: AndroidLanNetworkSnapshotProvider,
    ): LanNetworkSnapshotProvider

    @Binds
    @Singleton
    abstract fun bindNetworkCallbackRegistrar(
        implementation: AndroidNetworkCallbackRegistrar,
    ): NetworkCallbackRegistrar

    @Binds
    @Singleton
    abstract fun bindLanNetworkObserver(
        implementation: DefaultLanNetworkObserver,
    ): LanNetworkObserver

    @Binds
    @Singleton
    abstract fun bindLocalNamePublisher(
        implementation: AndroidLocalNamePublisher,
    ): LocalNamePublisher

    @Binds
    @Singleton
    abstract fun bindServerRuntimeFactory(
        implementation: KtorServerRuntimeFactory,
    ): ServerRuntimeFactory

    companion object {
        @Provides
        @ProductionServerPort
        fun provideProductionServerPort(): Int = DEFAULT_PRODUCTION_SERVER_PORT

        @Provides
        @Singleton
        fun provideWebAssetProvider(
            @ApplicationContext context: Context,
        ): WebAssetProvider = AssetManagerWebAssetProvider(context.assets)
    }
}
