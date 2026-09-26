package com.example.engine

enum class CommandPolicy {
    SAFE,
    REQUIRES_CONFIRMATION,
    BLOCKED
}

data class ValidationResult(
    val policy: CommandPolicy,
    val command: String,
    val reason: String
)

object CommandValidator {

    private val BLOCKED_PATTERNS = listOf(
        Regex("""\brm\b""", RegexOption.IGNORE_CASE),
        Regex("""\bformat\b""", RegexOption.IGNORE_CASE),
        Regex("""\bfactory[\s_-]*reset\b""", RegexOption.IGNORE_CASE),
        Regex("""\breboot\b""", RegexOption.IGNORE_CASE),
        Regex("""\bshutdown\b""", RegexOption.IGNORE_CASE),
        Regex("""\bsu\b""", RegexOption.IGNORE_CASE),
        Regex("""\bmkfs\b""", RegexOption.IGNORE_CASE),
        Regex("""\bdd\b""", RegexOption.IGNORE_CASE),
        Regex("""\bchmod\s+[0-7]{3,4}\s+/""", RegexOption.IGNORE_CASE),
        Regex("""\bsetprop\s+persist\.""", RegexOption.IGNORE_CASE)
    )

    private val SAFE_PATTERNS = listOf(
        Regex("""^getprop(\s+[\w.-]+)?$""", RegexOption.IGNORE_CASE),
        Regex("""^pm\s+list\s+packages(\s+.*)?$""", RegexOption.IGNORE_CASE),
        Regex("""^pm\s+path\s+[\w.]+$""", RegexOption.IGNORE_CASE),
        Regex("""^dumpsys(\s+[\w.-]+)?$""", RegexOption.IGNORE_CASE),
        Regex("""^input\s+swipe\s+\d+\s+\d+\s+\d+\s+\d+(\s+\d+)?$""", RegexOption.IGNORE_CASE),
        Regex("""^input\s+tap\s+\d+\s+\d+$""", RegexOption.IGNORE_CASE),
        Regex("""^input\s+keyevent\s+\d+$""", RegexOption.IGNORE_CASE),
        Regex("""^date(\s+.*)?$""", RegexOption.IGNORE_CASE),
        Regex("""^uptime$""", RegexOption.IGNORE_CASE),
        Regex("""^echo\s+.*$""", RegexOption.IGNORE_CASE),
        Regex("""^uname(\s+-a)?$""", RegexOption.IGNORE_CASE),
        Regex("""^whoami$""", RegexOption.IGNORE_CASE)
    )

    fun validate(command: String): ValidationResult {
        val trimmed = command.trim()

        if (trimmed.isEmpty()) {
            return ValidationResult(CommandPolicy.BLOCKED, trimmed, "Command is empty")
        }

        for (pattern in BLOCKED_PATTERNS) {
            if (pattern.containsMatchIn(trimmed)) {
                return ValidationResult(
                    CommandPolicy.BLOCKED,
                    trimmed,
                    "Command contains dangerous or forbidden directive"
                )
            }
        }

        for (pattern in SAFE_PATTERNS) {
            if (pattern.matches(trimmed)) {
                return ValidationResult(CommandPolicy.SAFE, trimmed, "Command matched safe policy allowlist")
            }
        }

        // Any other non-blocked command requires user confirmation
        return ValidationResult(
            CommandPolicy.REQUIRES_CONFIRMATION,
            trimmed,
            "Custom command requires user explicit confirmation"
        )
    }
}
