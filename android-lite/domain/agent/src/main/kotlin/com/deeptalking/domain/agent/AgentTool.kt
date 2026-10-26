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
 * `upsert_lorebook_entry`); the loop applies the latest non-null value at the end
 * of the run. Side-effect only — it is not shown to the model.
 */
data class AgentToolResult(
    val contentJson: String,
    val isError: Boolean = false,
    val updatedCharacter: com.deeptalking.core.model.Character? = null,
    /**
     * Set by `send_sticker` when the character chose a sticker to send as its
     * next message (legacy `toolState.stickerToSend`); the orchestrator surfaces
     * it so the UI can append the assistant sticker message.
     */
    val stickerFileRef: String? = null,
    /**
     * Set by tools that produce a picture the character wants to show **in this reply**
     * (`bilibili_video` 的 `frame_at`：把某一帧贴出来）。The orchestrator surfaces it so the
     * UI can append an assistant image bubble.
     */
    val imageFileRef: String? = null,
    /**
     * 一行**给用户看**的表情结果（"表情库 +1：无语（共 13 张）" / "没发出：库里没有「委屈」"）。
     *
     * 与 [contentJson] 的分工：contentJson 是给模型读的协议，这里是给气泡底部那行小字用的账。
     * 不进消息正文，也不进下一轮请求——否则角色会把自己看过的 UI 提示当台词复述。
     * 一轮里多次表情动作时，后写的覆盖先写的（最后一次动作的结果才是这轮的结果）。
     */
    val stickerNotice: String? = null,
)

/**
 * A pluggable agent capability. New features are added by implementing this
 * interface and registering it — the agent loop itself never changes.
 */
interface AgentTool {
    val definition: ToolDefinition

    /** Tools can opt in/out based on the current character or app state. */
    fun isEnabled(context: AgentContext): Boolean = true

    /**
     * Context-dependent definition. Defaults to the static [definition]; override it
     * when the schema itself must shrink with the context (`send_sticker`'s tag enum,
     * `submit_response`'s group-only blocks) so single-character requests stop paying
     * for text they cannot use.
     */
    fun definitionFor(context: AgentContext): ToolDefinition = definition

    suspend fun execute(call: ToolCall, context: AgentContext): AgentToolResult
}
