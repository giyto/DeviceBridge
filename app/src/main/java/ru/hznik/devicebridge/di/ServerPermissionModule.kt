package ru.hznik.devicebridge.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import android.os.Build
import javax.inject.Singleton
import ru.hznik.devicebridge.data.permission.AndroidPermissionChangeRegistrar
import ru.hznik.devicebridge.data.permission.AndroidServerPermissionGateway
import ru.hznik.devicebridge.data.permission.LocalNetworkPermissionObserver
import ru.hznik.devicebridge.data.permission.PermissionChangeRegistrar
import ru.hznik.devicebridge.data.permission.PermissionRevocationObserver
import ru.hznik.devicebridge.data.permission.ServerPermissionGateway
import ru.hznik.devicebridge.data.permission.ServerPermissionPolicy
import ru.hznik.devicebridge.data.permission.ServerPermissionRequestPlanner

@Module
@InstallIn(SingletonComponent::class)
abstract class ServerPermissionModule {

    @Binds
    @Singleton
    abstract fun bindServerPermissionGateway(
        implementation: AndroidServerPermissionGateway,
    ): ServerPermissionGateway

    @Binds
    @Singleton
    abstract fun bindPermissionChangeRegistrar(
        implementation: AndroidPermissionChangeRegistrar,
    ): PermissionChangeRegistrar

    companion object {
        @Provides
        @Singleton
        fun provideServerPermissionPolicy(): ServerPermissionPolicy =
            ServerPermissionPolicy()

        @Provides
        @Singleton
        fun provideServerPermissionRequestPlanner(
            policy: ServerPermissionPolicy,
        ): ServerPermissionRequestPlanner = ServerPermissionRequestPlanner(policy)

        @Provides
        @Singleton
        fun providePermissionRevocationObserver(
            registrar: PermissionChangeRegistrar,
            gateway: ServerPermissionGateway,
        ): PermissionRevocationObserver =
            LocalNetworkPermissionObserver(
                registrar = registrar,
                isPermissionRequired = { Build.VERSION.SDK_INT >= 37 },
                isLocalNetworkGranted = {
                    gateway.snapshot().localNetworkGranted
                },
            )
    }
}
