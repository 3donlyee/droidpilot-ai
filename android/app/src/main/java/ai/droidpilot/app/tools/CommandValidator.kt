package ai.droidpilot.app.tools

/**
 * Shell command policy for the run_shell() tool.
 *
 * SAFE                  → executed directly
 * REQUIRES_CONFIRMATION → executed only after the user explicitly approves in the Web UI
 * BLOCKED               → never executed
 *
 * Note: without root/Shizuku/ADB a regular app can only run a small subset of
 * shell utilities; the policy still applies so the same validation protects
 * the AdbBridge path when it becomes available.
 */
object CommandValidator {

    enum class Policy { SAFE, REQUIRES_CONFIRMATION, BLOCKED }

    private val BLOCKED = listOf(
        "rm ", "rm -", "rmdir", "mkfs", "dd ", "flash", "factory", "recovery",
        "reboot", "shutdown", "poweroff", "su ", "sudo", "chmod", "chown", "chgrp",
        "mount", "umount", "setprop", "stop ", "start ", "kill", "killall",
        "wipe", "format", "svc ", "sendevent", ">", ">>", "|", ";", "&&", "||", "`", "$("
    )

    private val SAFE = listOf(
        "getprop", "pm list packages", "dumpsys", "input swipe", "input tap",
        "input keyevent", "input text", "logcat -d", "uptime", "id", "whoami",
        "uname", "getenforce", "cat /system/build.prop", "wm size", "wm density"
    )

    fun classify(command: String): Policy {
        val c = command.trim().lowercase()
        if (c.isEmpty()) return Policy.BLOCKED
        if (BLOCKED.any { c.contains(it) }) return Policy.BLOCKED
        if (SAFE.any { c == it || c.startsWith(it) }) return Policy.SAFE
        // Unknown / sensitive commands need explicit user confirmation.
        return Policy.REQUIRES_CONFIRMATION
    }
}
