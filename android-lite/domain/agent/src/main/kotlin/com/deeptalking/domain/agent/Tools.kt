package com.deeptalking.domain.agent

import com.deeptalking.core.network.WebContentProvider
import com.deeptalking.domain.agent.sticker.StickerFetcher
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
 * Builds the default agent tool list (nine tools; see `docs/AGENT_ARCHITECTURE.md`, 工具清单).
 *
 * Read: `search_memory` / `web_search` / `web_fetch` / `search` / `hot_board` / `bilibili_video`.
 * Write: `memory_write` (memory) / `upsert_lorebook_entry` (world book).
 * Expression: `send_sticker` (on for single characters only; a group has no sticker library,
 * and an empty library still needs the tool so `query` can fetch the first sticker).
 *
 * `bilibili_video` is injected by the app (it needs the B 站文本源 + 总结用的辅助调用通道), so it
 * stays optional here: tests and builds without网络 wiring keep the eight-tool set unchanged.
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
    bilibiliVideo: AgentTool? = null,
    stickerFetcher: StickerFetcher? = null,
): List<AgentTool> = buildList {
    add(SearchMemoryTool(memory))
    add(WebSearchTool(web))
    add(WebFetchTool(web))
    add(SearchTool(web))
    add(HotBoardTool(web))
    if (bilibiliVideo != null) add(bilibiliVideo)
    add(MemoryWriteTool(memory))
    add(UpsertLorebookEntryTool())
    if (stickersEnabled) add(SendStickerTool(stickersEnabled, stickerFetcher))
}
