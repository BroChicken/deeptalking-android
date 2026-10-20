package com.deeptalking.domain.agent

import com.deeptalking.core.network.WebContentProvider
import com.deeptalking.domain.agent.tools.HotBoardTool
import com.deeptalking.domain.agent.tools.MemoryWriteTool
import com.deeptalking.domain.agent.tools.SearchMemoryTool
import com.deeptalking.domain.agent.tools.SearchTool
import com.deeptalking.domain.agent.tools.SendStickerTool
import com.deeptalking.domain.agent.tools.UpsertLorebookEntryTool
import com.deeptalking.domain.agent.tools.WebFetchTool
import com.deeptalking.domain.agent.tools.WebSearchTool
import com.deeptalking.domain.memory.MemoryService

/**
 * Builds the default agent tool list (eight tools; see `docs/REPLY_PIPELINE_PLAN.md` §10).
 *
 * Read: `search_memory` / `web_search` / `web_fetch` / `search` / `hot_board`.
 * Write: `memory_write` (memory) / `upsert_lorebook_entry` (world book).
 * Expression: `send_sticker` (only when the character owns stickers).
 *
 * `submit_response` is gone with the structured protocol: the main call is plain text,
 * and the turn worker (`TurnWorker`) owns dynamic state, quick replies, todos and
 * promise updates. Platform search and hot boards are one tool each with an explicit
 * `platform` enum — the tool list is small enough that the enum is cheaper than six names.
 */
fun defaultTools(
    memory: MemoryService,
    web: WebContentProvider?,
    stickersEnabled: Boolean,
): List<AgentTool> = buildList {
    add(SearchMemoryTool(memory))
    add(WebSearchTool(web))
    add(WebFetchTool(web))
    add(SearchTool(web))
    add(HotBoardTool(web))
    add(MemoryWriteTool(memory))
    add(UpsertLorebookEntryTool())
    if (stickersEnabled) add(SendStickerTool(stickersEnabled))
}
