package com.deeptalking.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 待办事项（todo）——与长期记忆**互相独立**的一条约定。
 *
 * 由用户或角色提出、双方同意、且对话里出现明确日期时才新增（见 `domain:memory` 的
 * `TodoPolicy` / turn worker 的 `submit_state.todos`）；完成/取消/改期同样由
 * `submit_state.todos` 处理（须引用户原话），角色编辑页的待办 tab 提供「完成 / 取消 / 删除」兜底。
 */
@Serializable
enum class TodoStatus {
    @SerialName("active") Active,
    @SerialName("done") Done,
    @SerialName("cancelled") Cancelled,
}

/** 谁提出的这条待办（`user` = 用户发起；`character` = 角色发起、用户应答同意）。 */
@Serializable
enum class TodoProposer {
    @SerialName("user") User,
    @SerialName("character") Character,
}

@Serializable
data class Todo(
    val id: String = "",
    /** 简短标题（如「交报告」）。 */
    val title: String = "",
    /** 详情，写绝对日期，不写「明天」这类相对词。 */
    val detail: String = "",
    /** **必填**：约定的期限（ISO 时间）。 */
    val dueAt: String = "",
    val status: TodoStatus = TodoStatus.Active,
    val proposer: TodoProposer = TodoProposer.User,
    /** 双方原话所在的消息 id（角色发起时角色本轮用 `current_response`）。 */
    val sourceMessageIds: List<String> = emptyList(),
    /** 逐字摘录的双方原话（用户发起 = 用户原话；角色发起 = 用户应答原话）。 */
    val evidence: String = "",
    val createdAt: String? = null,
    val updatedAt: String? = null,
    /** 转为 [TodoStatus.Done] / [TodoStatus.Cancelled] 的时刻：历史窗口与自动清理的基准。 */
    val closedAt: String? = null,
)
