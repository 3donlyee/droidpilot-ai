package com.example

import com.example.engine.CommandPolicy
import com.example.engine.CommandValidator
import org.junit.Assert.assertEquals
import org.junit.Test

class CommandValidatorTest {

    @Test
    fun testSafeCommands() {
        assertEquals(CommandPolicy.SAFE, CommandValidator.validate("getprop ro.product.model").policy)
        assertEquals(CommandPolicy.SAFE, CommandValidator.validate("pm list packages").policy)
        assertEquals(CommandPolicy.SAFE, CommandValidator.validate("dumpsys battery").policy)
        assertEquals(CommandPolicy.SAFE, CommandValidator.validate("input swipe 500 1500 500 500 300").policy)
        assertEquals(CommandPolicy.SAFE, CommandValidator.validate("uptime").policy)
    }

    @Test
    fun testBlockedCommands() {
        assertEquals(CommandPolicy.BLOCKED, CommandValidator.validate("rm -rf /data").policy)
        assertEquals(CommandPolicy.BLOCKED, CommandValidator.validate("factory reset").policy)
        assertEquals(CommandPolicy.BLOCKED, CommandValidator.validate("reboot recovery").policy)
        assertEquals(CommandPolicy.BLOCKED, CommandValidator.validate("su -c ls").policy)
    }

    @Test
    fun testConfirmationCommands() {
        assertEquals(CommandPolicy.REQUIRES_CONFIRMATION, CommandValidator.validate("curl https://evil.com").policy)
        assertEquals(CommandPolicy.REQUIRES_CONFIRMATION, CommandValidator.validate("am start -n custom/app").policy)
    }
}
