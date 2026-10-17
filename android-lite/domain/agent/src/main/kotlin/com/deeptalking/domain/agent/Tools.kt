package com.deeptalking.domain.agent

import com.deeptalking.core.network.WebContentProvider
import com.deeptalking.domain.agent.tools.AskUserTool
import com.deeptalking.domain.agent.tools.DeleteMemoryTool
import com.deeptalking.domain.agent.tools.GetCurrentTimeTool
import com.deeptalking.domain.agent.tools.HotBoardTool
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
    // 免登录的国内平台源。**一个工具一个平台**：模型不再通过 platform 参数挑平台，
    // 工具名本身就是语义（search_video=B站、search_music=网易云、search_posts=虎扑、
    // xxx_hot=对应热榜），因此不存在「选错平台」这一步。放在 web_search 之后、
    // submit_response 之前。
    add(
        SocialSearchTool(
            web,
            platform = "bilibili",
            name = "search_video",
            label = "B站（哔哩哔哩）",
            hint = "用户想找某个视频、某个UP主的视频时使用。返回标题与链接；视频链接可用 web_fetch 打开看标题、UP主、播放数据、简介与封面图。",
        ),
    )
    add(
        SocialSearchTool(
            web,
            platform = "netease",
            name = "search_music",
            label = "网易云音乐",
            hint = "用户想找某首歌、某个歌手的歌时使用。返回歌名、歌手与歌曲 id/链接；歌曲链接可用 web_fetch 打开看热评。",
        ),
    )
    add(
        SocialSearchTool(
            web,
            platform = "hupu",
            name = "search_posts",
            label = "虎扑",
            hint = "用户想找虎扑上的帖子、看大家怎么聊时使用。返回标题与链接；帖子链接可用 web_fetch 读正文与回帖。",
        ),
    )
    add(HotBoardTool(web, platform = "baidu", name = "baidu_hot", label = "百度热搜"))
    add(HotBoardTool(web, platform = "toutiao", name = "toutiao_hot", label = "今日头条热榜"))
    add(HotBoardTool(web, platform = "douyin", name = "douyin_hot", label = "抖音热榜"))
    add(HotBoardTool(web, platform = "zhihu", name = "zhihu_hot", label = "知乎热榜"))
    add(SubmitResponseTool())
}
