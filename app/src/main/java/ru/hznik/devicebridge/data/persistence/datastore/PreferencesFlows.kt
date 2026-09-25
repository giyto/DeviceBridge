package ru.hznik.devicebridge.data.persistence.datastore

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch

internal fun Flow<Preferences>.orEmptyOnIoError(): Flow<Preferences> =
    catch { failure ->
        if (failure is IOException) {
            emit(emptyPreferences())
        } else {
            throw failure
        }
    }
