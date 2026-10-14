package com.deeptalking.domain.agent

import com.deeptalking.core.network.WebContentProvider
import com.deeptalking.domain.agent.tools.AskUserTool
import com.deeptalking.domain.agent.tools.DeleteMemoryTool
import com.deeptalking.domain.agent.tools.GetCurrentTimeTool
import com.deeptalking.domain.agent.tools.HotTopicsTool
import com.deeptalking.domain.agent.tools.ListMemoriesTool
import com.deeptalking.domain.agent.tools.SearchMemoryTool
import com.deeptalking.domain.agent.tools.SendStickerTool
import com.deeptalking.domain.agent.tools.SetReminderTool
import com.deeptalking.domain.agent.tools.SocialSearchTool
import com.deeptalking.domain.agent.tools.SubmitResponseTool
import com.deeptalking.domain.agent.tools.UpdateCharacterFieldTool
import com.deeptalking.domain.agent.tools.UpdateMemoryTool
import com.deeptalking.domain.agent.tools.UpsertLorebookEntryTool
import com.deeptalking.domain.agent.tools.WebFetchTool
import com.deeptalking.domain.agent.tools.WebSearchTool
import com.deeptalking.domain.memory.MemoryService

/**
 * Builds the default agent tool list, mirroring `buildAgentTools` in
 * tool-definitions.js. `submit_response` is always appended last.
 */
fun defaultTools(
    memory: MemoryService,
    web: WebContentProvider?,
    stickersEnabled: Boolean,
): List<AgentTool> = buildList {
    add(GetCurrentTimeTool())
    add(SearchMemoryTool(memory))
    add(ListMemoriesTool(memory))
    add(DeleteMemoryTool(memory))
    add(SetReminderTool(memory))
    add(AskUserTool())
    add(UpdateCharacterFieldTool())
    add(UpsertLorebookEntryTool())
    add(WebFetchTool(web))
    add(UpdateMemoryTool(memory))
    if (stickersEnabled) add(SendStickerTool(stickersEnabled))
    add(WebSearchTool(web))
    // 免登录的国内平台源（无遗留 JS 对应物，见 DifferentialOracleTest.NATIVE_ONLY_TOOLS）：
    // 热榜与社区检索。放在 web_search 之后、submit_response 之前。
    add(HotTopicsTool(web))
    add(SocialSearchTool(web))
    add(SubmitResponseTool())
}
