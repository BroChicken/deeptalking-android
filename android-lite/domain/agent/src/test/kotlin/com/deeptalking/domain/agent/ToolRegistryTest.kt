package com.deeptalking.domain.agent

import com.deeptalking.core.model.Character
import com.deeptalking.engine.ondevice.ToolCall
import com.deeptalking.engine.ondevice.ToolDefinition
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolRegistryTest {

    private val fake = object : AgentTool {
        override val definition = ToolDefinition(
            name = "echo",
            description = "Echoes back",
            parametersJson = "{}",
        )

        override suspend fun execute(call: ToolCall, context: AgentContext): AgentToolResult =
            AgentToolResult("""{"ok":true}""")
    }

    private val context = AgentContext(
        character = Character(id = "c1"),
        activeCharacterId = "c1",
    )

    @Test
    fun definitionsReturnsRegisteredTools() {
        val registry = ToolRegistry(listOf(fake))
        assertEquals(1, registry.definitions(context).size)
        assertEquals("echo", registry.definitions(context).first().name)
    }

    @Test
    fun executeDelegatesToMatchingTool() = runBlocking {
        val registry = ToolRegistry(listOf(fake))
        val result = registry.execute(ToolCall("1", "echo", "{}"), context)
        assertEquals("""{"ok":true}""", result.contentJson)
        assertEquals(false, result.isError)
    }
}
