package com.deeptalking.domain.agent

import com.deeptalking.core.model.Character
import com.deeptalking.engine.ondevice.ToolCall
import com.deeptalking.engine.ondevice.ToolDefinition

/** Everything a tool may need to run a single call. */
data class AgentContext(
    val character: Character,
    val activeCharacterId: String,
)

/**
 * Result of executing a tool; [contentJson] is fed back to the model.
 *
 * [updatedCharacter] lets a tool persist a mutated character (e.g.
 * `update_character_field` / `upsert_lorebook_entry`); the loop applies the
 * latest non-null value at the end of the run. Side-effect only — it is not
 * shown to the model.
 */
data class AgentToolResult(
    val contentJson: String,
    val isError: Boolean = false,
    val updatedCharacter: com.deeptalking.core.model.Character? = null,
)

/**
 * A pluggable agent capability. New features are added by implementing this
 * interface and registering it — the agent loop itself never changes.
 */
interface AgentTool {
    val definition: ToolDefinition

    /** Tools can opt in/out based on the current character or app state. */
    fun isEnabled(context: AgentContext): Boolean = true

    suspend fun execute(call: ToolCall, context: AgentContext): AgentToolResult
}
