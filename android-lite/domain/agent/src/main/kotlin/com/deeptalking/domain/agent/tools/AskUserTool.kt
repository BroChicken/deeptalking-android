package com.deeptalking.domain.agent.tools

import com.deeptalking.domain.agent.AgentContext
import com.deeptalking.domain.agent.AgentTool
import com.deeptalking.domain.agent.AgentToolResult
import com.deeptalking.engine.ondevice.ToolCall
import com.deeptalking.engine.ondevice.ToolDefinition

/** Port of the `ask_user` branch in tool-execution.js. */
class AskUserTool : AgentTool {

    override val definition = ToolDefinition(
        name = "ask_user",
        description = "当记忆缺失、用户表述含糊或无法可靠推断某件关键事实时，向用户提出一个简短澄清问题，避免凭空编造。仅在确实需要澄清且追问不显得突兀时使用；不要滥用，也不要用它代替正常的角色回复。",
        parametersJson = """{"type":"object","properties":{"question":{"type":"string","description":"要问用户的问题"}},"required":["question"],"additionalProperties":false}""",
    )

    override suspend fun execute(call: ToolCall, context: AgentContext): AgentToolResult {
        val args = ToolArgs.parse(call.arguments)
        val question = args.let { ToolArgs.string(it, "question") }.trim().take(200)
        if (question.isEmpty()) return AgentToolResult(errorJson("缺少问题 question"))
        return AgentToolResult("""{"ok":true,"question":${quote(question)}}""")
    }
}
