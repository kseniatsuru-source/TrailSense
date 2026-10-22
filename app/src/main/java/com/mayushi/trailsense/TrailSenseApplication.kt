package com.mayushi.trailsense

import android.app.Application
import androidx.room.withTransaction
import com.mayushi.trailsense.data.TrailDatabase
import com.mayushi.trailsense.data.TrailRepository
import com.mayushi.trailsense.exchange.TelegramExchangeManager
import com.mayushi.trailsense.network.*
import com.mayushi.trailsense.reminders.PillReminderScheduler
import org.osmdroid.config.Configuration

class TrailSenseApplication : Application() {
    val database by lazy { TrailDatabase.get(this) }
    val repository by lazy { TrailRepository(database.trailDao(), contentResolver, filesDir) {
        database.withTransaction { PendingAnalysisRefresh(database.trailDao(), preferences).refresh() }
    } }
    val preferences by lazy { AppPreferences(this) }
    val secretStore by lazy { SecureSecretStore(this) }
    val hermesClient by lazy { HermesClient() }
    val aiManager by lazy { AiAnalysisManager(this, database.trailDao(), preferences) }
    val telegramExchange by lazy { TelegramExchangeManager(this, database, preferences) }

    override fun onCreate() {
        super.onCreate()
        Configuration.getInstance().userAgentValue = packageName
        preferences.directHermesEnabled = false
        PillReminderScheduler.createChannel(this)
    }
}
