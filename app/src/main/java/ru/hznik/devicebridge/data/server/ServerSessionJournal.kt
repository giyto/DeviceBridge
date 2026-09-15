package ru.hznik.devicebridge.data.server

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

interface ServerSessionJournal {
    fun consumeInterruptedSession(): Boolean

    fun markRunning()

    fun clear()
}

@Singleton
class AndroidServerSessionJournal @Inject constructor(
    @ApplicationContext context: Context,
) : ServerSessionJournal {

    private val preferences = context.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    @Synchronized
    override fun consumeInterruptedSession(): Boolean {
        val wasRunning = preferences.getBoolean(KEY_SESSION_RUNNING, false)
        if (wasRunning) {
            preferences.edit().putBoolean(KEY_SESSION_RUNNING, false).commit()
        }
        return wasRunning
    }

    override fun markRunning() {
        preferences.edit().putBoolean(KEY_SESSION_RUNNING, true).commit()
    }

    override fun clear() {
        preferences.edit().putBoolean(KEY_SESSION_RUNNING, false).commit()
    }

    private companion object {
        const val PREFERENCES_NAME = "server_session_journal"
        const val KEY_SESSION_RUNNING = "session_running"
    }
}
