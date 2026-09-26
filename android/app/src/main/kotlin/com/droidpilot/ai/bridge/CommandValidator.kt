package com.droidpilot.ai.bridge

import com.droidpilot.ai.util.Logger
import kotlinx.serialization.json.JsonObject

/**
 * DroidPilot AI — Command Validator.
 *
 * Classifies shell commands into three categories:
 *
 *   SAFE                    → run immediately
 *   REQUIRES_CONFIRMATION  → must show a UI dialog; user must accept
 *   BLOCKED                 → refused outright, never executed
 *
 * Classification policy is a small built-in map keyed on the FIRST TOKEN(S)
 * of the command. This is intentionally simple and conservative — when in
 * doubt, REQUIRES_CONFIRMATION.
 *
 * Examples (see [DEFAULT_POLICY]):
 *   getprop                          → SAFE
 *   pm list packages                 → SAFE
 *   dumpsys activity                 → SAFE
 *   input tap 500 500                → SAFE
 *   input swipe 100 200 100 800 200 → SAFE
 *   pm clear com.foo.bar            → REQUIRES_CONFIRMATION
 *   settings put system foo 1       → REQUIRES_CONFIRMATION
 *   rm                               → BLOCKED
 *   rm -rf                           → BLOCKED
 *   factory reset                    → BLOCKED
 *   reboot                           → BLOCKED
 *   shutdown                         → BLOCKED
 */
class CommandValidator {

    enum class CommandPolicy {
        SAFE,
        REQUIRES_CONFIRMATION,
        BLOCKED
    }

    enum class ValidationResult {
        ALLOWED,
        NEEDS_CONFIRMATION,
        REJECTED
    }

    /**
     * Classify a shell command into [CommandPolicy].
     *
     * Algorithm:
     *  1. lowercase the command and strip leading "sh -c " / "su -c " wrappers
     *  2. iterate over the policy entries (ordered: most specific first)
     *  3. return the first matching policy
     *  4. default = REQUIRES_CONFIRMATION (defensive — never auto-allow unknown)
     */
    fun classify(rawCommand: String): CommandPolicy {
        val cmd = normalise(rawCommand)
        if (cmd.isBlank()) return CommandPolicy.BLOCKED

        // Reject obvious destructive patterns early.
        for (needle in DESTRUCTIVE_PATTERNS) {
            if (cmd.contains(needle)) {
                return CommandPolicy.BLOCKED
            }
        }

        // Walk the policy table — entries are ordered by specificity.
        for ((prefix, policy) in DEFAULT_POLICY) {
            if (cmd.startsWith(prefix)) {
                return policy
            }
        }
        return CommandPolicy.REQUIRES_CONFIRMATION
    }

    /**
     * Validate a tool invocation. Only `run_shell` is currently checked;
     * all other tools are allowed (their own implementation enforces safety).
     *
     * Returns the [ValidationResult] the executor should respect.
     */
    fun validate(toolName: String, args: JsonObject): ValidationResult {
        if (toolName != "run_shell") return ValidationResult.ALLOWED
        val command = args["command"]?.toString()?.trim('"', ' ').orEmpty()
        return when (classify(command)) {
            CommandPolicy.SAFE -> ValidationResult.ALLOWED
            CommandPolicy.REQUIRES_CONFIRMATION -> ValidationResult.NEEDS_CONFIRMATION
            CommandPolicy.BLOCKED -> ValidationResult.REJECTED
        }
    }

    /**
     * Strip whitespace, lowercase, and remove common wrappers
     * (`sh -c`, `su -c`, leading `sudo`).
     */
    private fun normalise(rawCommand: String): String {
        var s = rawCommand.trim().lowercase()
        // strip wrappers
        for (prefix in WRAPPERS) {
            if (s.startsWith(prefix)) {
                s = s.substring(prefix.length).trim()
                // drop surrounding quotes if present
                if (s.length >= 2 && (s.startsWith('"') && s.endsWith('"'))) {
                    s = s.substring(1, s.length - 1)
                } else if (s.length >= 2 && (s.startsWith('\'') && s.endsWith('\''))) {
                    s = s.substring(1, s.length - 1)
                }
            }
        }
        return s
    }

    companion object {
        private const val TAG = "CommandValidator"

        /** Common command wrappers we strip before classification. */
        private val WRAPPERS = listOf("sh -c ", "su -c ", "/system/bin/sh -c ", "sudo ")

        /**
         * Patterns that mark a command as BLOCKED unconditionally, regardless
         * of where they appear in the command.
         */
        private val DESTRUCTIVE_PATTERNS = listOf(
            "rm -rf",
            "rm -r",
            "rm -f",
            "format",
            "factory reset",
            "reboot",
            "shutdown",
            "dd if=",
            "mount -o remount",
            "setenforce 0",
            "magisk",
            "supersu"
        )

        /**
         * Ordered prefix → policy table.
         * Most specific prefixes MUST come first (e.g. `pm clear` before `pm`).
         */
        private val DEFAULT_POLICY: List<Pair<String, CommandPolicy>> = listOf(
            // --- SAFE: read-only diagnostics ---
            "getprop" to CommandPolicy.SAFE,
            "pm list packages" to CommandPolicy.SAFE,
            "pm path" to CommandPolicy.SAFE,
            "pm dump" to CommandPolicy.SAFE,
            "dumpsys" to CommandPolicy.SAFE,
            "cmd package list" to CommandPolicy.SAFE,
            "am stack list" to CommandPolicy.SAFE,
            "settings get" to CommandPolicy.SAFE,
            "settings list" to CommandPolicy.SAFE,
            "wm size" to CommandPolicy.SAFE,
            "wm density" to CommandPolicy.SAFE,
            "content query" to CommandPolicy.SAFE,
            "uiautomator dump" to CommandPolicy.SAFE,
            "screencap" to CommandPolicy.SAFE,
            "input tap" to CommandPolicy.SAFE,
            "input swipe" to CommandPolicy.SAFE,
            "input keyevent" to CommandPolicy.SAFE,
            "input text" to CommandPolicy.SAFE,
            "am start -n" to CommandPolicy.SAFE,
            "am force-stop" to CommandPolicy.SAFE,
            "ls" to CommandPolicy.SAFE,
            "cat " to CommandPolicy.SAFE,
            "echo" to CommandPolicy.SAFE,
            "ps " to CommandPolicy.SAFE,
            "ps" to CommandPolicy.SAFE,
            "df" to CommandPolicy.SAFE,
            "uptime" to CommandPolicy.SAFE,
            "free" to CommandPolicy.SAFE,
            "top -n 1" to CommandPolicy.SAFE,
            "logcat -d" to CommandPolicy.SAFE,
            "ifconfig" to CommandPolicy.SAFE,
            "ip addr" to CommandPolicy.SAFE,

            // --- REQUIRES_CONFIRMATION: mutating but reversible ---
            "pm clear" to CommandPolicy.REQUIRES_CONFIRMATION,
            "pm enable" to CommandPolicy.REQUIRES_CONFIRMATION,
            "pm disable" to CommandPolicy.REQUIRES_CONFIRMATION,
            "pm grant" to CommandPolicy.REQUIRES_CONFIRMATION,
            "pm revoke" to CommandPolicy.REQUIRES_CONFIRMATION,
            "pm uninstall" to CommandPolicy.REQUIRES_CONFIRMATION,
            "pm install" to CommandPolicy.REQUIRES_CONFIRMATION,
            "settings put" to CommandPolicy.REQUIRES_CONFIRMATION,
            "settings delete" to CommandPolicy.REQUIRES_CONFIRMATION,
            "settings reset" to CommandPolicy.REQUIRES_CONFIRMATION,
            "am broadcast" to CommandPolicy.REQUIRES_CONFIRMATION,
            "am set-debug-app" to CommandPolicy.REQUIRES_CONFIRMATION,
            "cmd notification post" to CommandPolicy.REQUIRES_CONFIRMATION,
            "service call" to CommandPolicy.REQUIRES_CONFIRMATION,
            "wm density " to CommandPolicy.REQUIRES_CONFIRMATION,
            "wm size " to CommandPolicy.REQUIRES_CONFIRMATION,
            "cmd appops set" to CommandPolicy.REQUIRES_CONFIRMATION,
            "cmd input " to CommandPolicy.REQUIRES_CONFIRMATION,
            "kill " to CommandPolicy.REQUIRES_CONFIRMATION,

            // --- BLOCKED (explicit) ---
            "rm" to CommandPolicy.BLOCKED,
            "rmdir" to CommandPolicy.BLOCKED,
            "rm -" to CommandPolicy.BLOCKED,
            "mkfs" to CommandPolicy.BLOCKED,
            "reboot" to CommandPolicy.BLOCKED,
            "shutdown" to CommandPolicy.BLOCKED,
            "am broadcast -a android.intent.action.MASTER_CLEAR" to CommandPolicy.BLOCKED
        )
    }
}
