package ai.droidpilot.app.tools

/**
 * Shell command policy for the run_shell() tool.
 *
 * SAFE                  → executed directly
 * REQUIRES_CONFIRMATION → executed when the worker marks the command approved
 *                         (cmd.approved) or the model passes approved=true per
 *                         the tool schema ("set true ONLY after explicit user
 *                         approval in chat")
 * BLOCKED               → never executed
 *
 * FIX (v1.1.1): pipes/redirects/chains used to be BLOCKED, which silently
 * killed perfectly ordinary AI commands like `dumpsys battery | grep level`
 * or `am start -n …`. They are now REQUIRES_CONFIRMATION instead, and
 * start/stop/kill are matched as LEADING TOKENS only (so `am start` is no
 * longer caught by a substring "start " rule).
 */
object CommandValidator {

    enum class Policy { SAFE, REQUIRES_CONFIRMATION, BLOCKED }

    /** Destructive / state-changing — always rejected, even with approval. */
    private val BLOCKED = listOf(
        "rm ", "rm -", "rmdir", "mkfs", "dd ", "flash", "factory", "recovery",
        "reboot", "shutdown", "poweroff", "su ", "sudo", "chmod", "chown", "chgrp",
        "mount", "umount", "setprop", "wipe", "format", "killall", "sendevent"
    )

    /** Needs confirmation: shell metacharacters + risky leading commands. */
    private val CONFIRM_SUBSTR = listOf(
        ">", ">>", "|", ";", "&&", "||", "`", "$("
    )

    /** Matched as the FIRST token only — "am start …" is NOT "start". */
    private val CONFIRM_TOKENS = setOf("start", "stop", "kill", "svc")

    private val SAFE = listOf(
        "getprop", "pm list packages", "dumpsys", "input swipe", "input tap",
        "input keyevent", "input text", "logcat -d", "uptime", "id", "whoami",
        "uname", "getenforce", "cat /system/build.prop", "wm size", "wm density"
    )

    fun classify(command: String): Policy {
        val c = command.trim().lowercase()
        if (c.isEmpty()) return Policy.BLOCKED
        if (BLOCKED.any { c.contains(it) }) return Policy.BLOCKED
        val firstToken = c.split(Regex("\\s+")).firstOrNull()
        if (CONFIRM_SUBSTR.any { c.contains(it) } ||
            (firstToken != null && firstToken in CONFIRM_TOKENS)
        ) return Policy.REQUIRES_CONFIRMATION
        if (SAFE.any { c == it || c.startsWith(it) }) return Policy.SAFE
        // Unknown / sensitive commands need explicit confirmation.
        return Policy.REQUIRES_CONFIRMATION
    }
}
