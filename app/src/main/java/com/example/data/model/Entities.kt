package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "logs")
data class LogEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val level: String = "INFO", // INFO, WARN, ERROR, DEBUG, ACTION
    val source: String = "SYSTEM", // SYSTEM, ACCESSIBILITY, AI, WORKER, TOOL
    val message: String,
    val details: String? = null
)

@Entity(tableName = "commands")
data class CommandRecord(
    @PrimaryKey
    val commandId: String,
    val tool: String,
    val argumentsJson: String = "{}",
    val status: String = "PENDING", // PENDING, RUNNING, SUCCESS, FAILED
    val resultJson: String? = null,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null
)

@Entity(tableName = "config")
data class DeviceConfig(
    @PrimaryKey
    val key: String,
    val value: String
)
