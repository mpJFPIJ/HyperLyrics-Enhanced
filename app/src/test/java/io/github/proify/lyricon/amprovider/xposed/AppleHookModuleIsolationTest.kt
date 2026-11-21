package io.github.proify.lyricon.amprovider.xposed

import io.github.proify.lyricon.amprovider.xposed.hooks.FunctionalAppleMusicHookModule
import org.junit.Assert.assertEquals
import org.junit.Test

class AppleHookModuleIsolationTest {
    @Test
    fun `one failed native signature cannot skip later independent hook families`() {
        val installed = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val modules = listOf(
            FunctionalAppleMusicHookModule("first") { installed += "first" },
            FunctionalAppleMusicHookModule("broken") { error("method signature changed") },
            FunctionalAppleMusicHookModule("last") { installed += "last" },
        )

        val failed = installIndependentAppleHookModules(
            modules = modules,
            install = { module -> module.installHooks() },
            onFailure = { id, _ -> errors += id },
        )

        assertEquals(listOf("first", "last"), installed)
        assertEquals(listOf("broken"), errors)
        assertEquals(listOf("broken"), failed)
    }
}
