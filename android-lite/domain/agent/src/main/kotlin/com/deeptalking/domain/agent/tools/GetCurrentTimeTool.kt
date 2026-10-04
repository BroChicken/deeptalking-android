package com.deeptalking.domain.agent.tools

import com.deeptalking.domain.agent.AgentContext
import com.deeptalking.domain.agent.AgentTool
import com.deeptalking.domain.agent.AgentToolResult
import com.deeptalking.domain.agent.prompts.TimeContext
import com.deeptalking.engine.ondevice.ToolCall
import com.deeptalking.engine.ondevice.ToolDefinition
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Port of the `get_current_time` branch in tool-execution.js. */
class GetCurrentTimeTool : AgentTool {

    override val definition = ToolDefinition(
        name = "get_current_time",
        description = "获取当前本地时间与日期（含时区、星期、ISO时间）。用于回答\"现在几点/几号/星期几/距离某个时间多久\"等问题。",
        parametersJson = """{"type":"object","properties":{},"additionalProperties":false}""",
    )

    override suspend fun execute(call: ToolCall, context: AgentContext): AgentToolResult {
        val time = TimeContext.snapshot()
        val json = buildJsonObject {
            put("local", time.local)
            put("iso", time.iso)
            put("timeZone", time.timeZone)
            put("offset", time.offset)
        }.toString()
        return AgentToolResult(json)
    }
}
