package com.example.data.repository

import com.example.data.db.AppDatabase
import com.example.data.model.CommandRecord
import com.example.data.model.DeviceConfig
import com.example.data.model.LogEntry
import kotlinx.coroutines.flow.Flow

class DroidPilotRepository(private val db: AppDatabase) {

    val recentLogs: Flow<List<LogEntry>> = db.logDao().getRecentLogs()
    val recentCommands: Flow<List<CommandRecord>> = db.commandDao().getRecentCommands()
    val allConfigs: Flow<List<DeviceConfig>> = db.configDao().getAllConfig()

    suspend fun log(level: String, source: String, message: String, details: String? = null) {
        db.logDao().insertLog(
            LogEntry(
                level = level,
                source = source,
                message = message,
                details = details
            )
        )
    }

    suspend fun clearLogs() {
        db.logDao().clearLogs()
    }

    suspend fun recordCommand(command: CommandRecord) {
        db.commandDao().insertCommand(command)
    }

    suspend fun updateCommand(command: CommandRecord) {
        db.commandDao().updateCommand(command)
    }

    suspend fun getConfig(key: String, defaultValue: String): String {
        return db.configDao().getValue(key) ?: defaultValue
    }

    suspend fun setConfig(key: String, value: String) {
        db.configDao().setConfig(DeviceConfig(key, value))
    }
}
