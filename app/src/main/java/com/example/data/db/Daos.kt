package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.CommandRecord
import com.example.data.model.DeviceConfig
import com.example.data.model.LogEntry
import kotlinx.coroutines.flow.Flow

@Dao
interface LogDao {
    @Query("SELECT * FROM logs ORDER BY id DESC LIMIT 500")
    fun getRecentLogs(): Flow<List<LogEntry>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: LogEntry): Long

    @Query("DELETE FROM logs")
    suspend fun clearLogs()
}

@Dao
interface CommandDao {
    @Query("SELECT * FROM commands ORDER BY createdAt DESC LIMIT 100")
    fun getRecentCommands(): Flow<List<CommandRecord>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCommand(command: CommandRecord)

    @Update
    suspend fun updateCommand(command: CommandRecord)

    @Query("SELECT * FROM commands WHERE commandId = :commandId")
    suspend fun getCommandById(commandId: String): CommandRecord?

    @Query("DELETE FROM commands")
    suspend fun clearCommands()
}

@Dao
interface ConfigDao {
    @Query("SELECT value FROM config WHERE `key` = :key")
    suspend fun getValue(key: String): String?

    @Query("SELECT * FROM config")
    fun getAllConfig(): Flow<List<DeviceConfig>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setConfig(config: DeviceConfig)
}
