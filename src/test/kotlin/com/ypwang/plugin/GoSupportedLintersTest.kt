package com.ypwang.plugin

import com.intellij.openapi.command.impl.DummyProject
import com.ypwang.plugin.platform.platformFactory
import org.junit.Assert
import org.junit.Test

class GoSupportedLintersTest {
    @Test
    fun linters() {
        val platform = platformFactory(DummyProject.getInstance())
        val ls = parseLinters(
            DummyProject.getInstance(),
            platform.runProcess(listOf("golangci-lint", "linters"), null, mapOf())
        )
        Assert.assertNotNull(ls.single { it.name == "golint" })
    }
}