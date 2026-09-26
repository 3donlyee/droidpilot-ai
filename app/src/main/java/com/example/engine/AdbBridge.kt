package com.example.engine

import android.content.Context
import android.os.Build
import java.io.BufferedReader
import java.io.InputStreamReader

data class AdbStatus(
    val isAvailable: Boolean,
    val mode: String, // "ACCESSIBILITY_FALLBACK", "LOCAL_SHELL", "WIRELESS_DEBUG"
    val description: String
)

class AdbBridge(private val context: Context) {

    fun checkStatus(): AdbStatus {
        return try {
            val process = Runtime.getRuntime().exec("which sh")
            val exitCode = process.waitFor()
            if (exitCode == 0) {
                AdbStatus(
                    isAvailable = true,
                    mode = "LOCAL_SHELL",
                    description = "Standard local shell environment available on Android ${Build.VERSION.RELEASE} (OPPO Reno5 / No Root)"
                )
            } else {
                AdbStatus(
                    isAvailable = false,
                    mode = "ACCESSIBILITY_FALLBACK",
                    description = "Using AccessibilityService automation mode"
                )
            }
        } catch (_: Exception) {
            AdbStatus(
                isAvailable = false,
                mode = "ACCESSIBILITY_FALLBACK",
                description = "Running in pure Accessibility gesture mode"
            )
        }
    }

    fun executeShell(command: String): ShellExecutionResult {
        val validation = CommandValidator.validate(command)
        if (validation.policy == CommandPolicy.BLOCKED) {
            return ShellExecutionResult(
                command = command,
                exitCode = -1,
                output = "",
                error = "BLOCKED: ${validation.reason}",
                policy = validation.policy
            )
        }

        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val errReader = BufferedReader(InputStreamReader(process.errorStream))

            val output = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }

            val error = StringBuilder()
            while (errReader.readLine().also { line = it } != null) {
                error.append(line).append("\n")
            }

            val exitCode = process.waitFor()
            ShellExecutionResult(
                command = command,
                exitCode = exitCode,
                output = output.toString().trim(),
                error = error.toString().trim().ifEmpty { null },
                policy = validation.policy
            )
        } catch (e: Exception) {
            ShellExecutionResult(
                command = command,
                exitCode = -1,
                output = "",
                error = "Execution error: ${e.message}",
                policy = validation.policy
            )
        }
    }
}

data class ShellExecutionResult(
    val command: String,
    val exitCode: Int,
    val output: String,
    val error: String? = null,
    val policy: CommandPolicy
)
