package com.deeptalking.domain.memory

import com.deeptalking.core.common.AppLimits
import com.deeptalking.core.model.Character
import com.deeptalking.core.model.Todo
import com.deeptalking.core.model.TodoProposer
import com.deeptalking.core.model.TodoStatus

/**
 * 待办（todo）的独立策略：新建闸门、状态流转、保留窗口。
 *
 * 待办与长期记忆**互相独立**：它不读 `longTerm`，也不写 `longTerm`；只有「对话里出现明确
 * 期限」且「双方同意」时才新增。两条写入路径：
 * - 新增/改期/完成/取消 —— turn worker 的 `submit_state.todos`（[validateTodoCreation] /
 *   [todoStatusOf] + [dueAtBackedByEvidence]）。
 * 角色编辑页的待办 tab 提供「完成 / 取消 / 删除」兜底。
 */
object TodoPolicy {

    /** 一天毫秒数（保留窗口用）。 */
    private const val DAY_MILLIS = 86_400_000L

    /** 新建待办的判定结果。[keywordAgreement] 只是关键词侧的确认信号，不是准入依据。 */
    sealed interface Creation {
        data class Accepted(
            val sources: List<SourceRef>,
            val keywordAgreement: Boolean = false,
        ) : Creation

        data class Rejected(val reason: String) : Creation
    }

    /**
     * 新建闸门。**「是否双方同意」以模型的判断为主**（模型必须引用真实用户消息里的逐字原话，
     * 这是硬校验；它选了 `proposer` 就代表它判定双方谈成了）。关键词只做其中**一环**：用来
     * 拦下"用户明确拒绝"的情况，命中同意词则额外确认——关键词覆盖不到的说法一律不否决，
     * 交给模型判断。
     *
     * 要求：
     * 1. **期限必填**，且能在对话原话里定位（相对词按消息时间换算后核对日期）；
     * 2. 用户发起（[TodoProposer.User]）：用户原话里同时含期限与提议/承诺；
     * 3. 角色发起（[TodoProposer.Character]）：必须附**用户应答的原话**，且该原话不得是明确
     *    拒绝；期限落在双方任一方的原话里即可（角色上一轮的提议也在 `character.instant` 里，
     *    作为期限锚点）。
     */
    fun validateTodoCreation(
        character: Character,
        proposer: TodoProposer,
        sourceIds: List<String>,
        evidence: String,
        dueAtIso: String?,
        sources: Map<String, SourceRef> = knownSources(character),
    ): Creation {
        if (dueAtIso.isNullOrBlank() || parseZoned(dueAtIso) == null) {
            return Creation.Rejected("待办必须有明确期限（请与用户确认具体日期后再记）")
        }
        val ids = sourceIds.map { it.trim() }.filter { it.isNotEmpty() }
        // A character-proposed todo was proposed *last* turn, so the model cites the user's
        // agreeing message; `hasValidUserEvidence` is exactly "evidence is verbatim in a user
        // message", which is the user side of the agreement for both proposers.
        val resolved = hasValidUserEvidence(character, ids, evidence, sources)
            ?: return Creation.Rejected(
                if (proposer == TodoProposer.Character) {
                    "角色发起必须附用户应答的原话：请把用户回应的话填进 sourceMessageIds 与 evidence"
                } else {
                    "无法核对用户原话：请附用户提出该待办的消息ID与逐字原话"
                },
            )
        val userSources = resolved.filter { it.role == PROMISOR_USER }
        if (proposer == TodoProposer.Character) {
            // 关键词只做一环：明确的拒绝/取消直接否决（"不可以"不能被当成答应）；
            // 其余说法（包括关键词没覆盖到的同意表达）不否决——同意与否由模型判断。
            if (userSources.any { isUserRefusal(it.text) }) {
                return Creation.Rejected("用户明确拒绝/取消了这件事，不要记为待办")
            }
            // 期限：双方任一方说过即可 —— 用户应答里说，或角色上一轮提议里说。
            val dateSources = userSources + latestAssistantSource(sources)
            if (!dueAtBackedByEvidence(dateSources, dueAtIso)) {
                return Creation.Rejected("期限核对不了：对话里没有可解析的日期/时间词，或与该日期不一致")
            }
            return Creation.Accepted(resolved, keywordAgreement = userSources.any { isUserAgreement(it.text) })
        }
        if (!dueAtBackedByEvidence(userSources, dueAtIso)) {
            return Creation.Rejected("期限核对不了：用户原话里没有可解析的日期/时间词，或与用户说的不是同一天")
        }
        return Creation.Accepted(resolved)
    }

    /** Maps a `todoUpdates.status` token to a terminal [TodoStatus], or null when unrecognized. */
    fun todoStatusOf(raw: String): TodoStatus? = when (raw.trim().lowercase()) {
        "done", "completed", "complete", "finished", "完成" -> TodoStatus.Done
        "cancelled", "canceled", "cancel", "closed", "取消", "关闭" -> TodoStatus.Cancelled
        else -> null
    }

    /**
     * 列表可见性 / 自动清理：保留仍未结束的条目，以及**关闭未满
     * [AppLimits.Todo.CLOSED_RETENTION_DAYS] 天**的已完成/已取消条目；更老的关闭条目丢弃。
     *
     * 关闭时间解析不出来时**保留**（宁可多留也不误删），所以这既是列表过滤器也是清理函数。
     */
    fun pruneClosedTodos(
        todos: List<Todo>,
        nowMillis: Long = System.currentTimeMillis(),
    ): List<Todo> {
        val cutoff = nowMillis - AppLimits.Todo.CLOSED_RETENTION_DAYS * DAY_MILLIS
        return todos.filter { todo ->
            if (todo.status == TodoStatus.Active) return@filter true
            val closed = parseZoned(todo.closedAt)?.toInstant()?.toEpochMilli()
                ?: parseZoned(todo.updatedAt)?.toInstant()?.toEpochMilli()
            closed == null || closed >= cutoff
        }
    }

    /** 已完成 / 已取消（关闭时刻）是否还落在保留窗口内。 */
    fun isClosedWithinRetention(todo: Todo, nowMillis: Long = System.currentTimeMillis()): Boolean =
        todo.status == TodoStatus.Active || pruneClosedTodos(listOf(todo), nowMillis).isNotEmpty()

    /** The most recent assistant message the sources know about (the character's last reply). */
    private fun latestAssistantSource(sources: Map<String, SourceRef>): List<SourceRef> =
        sources.values.lastOrNull { it.role == "assistant" }?.let { listOf(it) } ?: emptyList()

    /**
     * 关键词侧的**同意信号**（只是准入的其中一环，覆盖不到的说法不否决）：命中明确的同意词，
     * 或整条就是"好/行/嗯"这类应声；明确的拒绝词优先。
     */
    fun isUserAgreement(text: String?): Boolean {
        val value = text.orEmpty().trim()
        if (value.isEmpty()) return false
        if (isUserRefusal(value)) return false
        if (BARE_AGREEMENT.matches(value)) return true
        return AGREEMENT.containsMatchIn(value)
    }

    /**
     * 关键词侧的**拒绝信号**（准入里唯一是否决项）："不可以"这类明确否定必须拦下——它字面上
     * 含"可以"，模型也可能看走眼。命中即否决，其余情况交给模型判断。
     */
    fun isUserRefusal(text: String?): Boolean {
        val value = text.orEmpty().trim()
        return value.isNotEmpty() && REFUSAL.containsMatchIn(value)
    }

    private val AGREEMENT = Regex(
        "同意|答应|可以|没问题|好的|好啊|好呀|好吧|好嘞|好咧|好滴|行吧|行啊|行呀|一言为定|说定了|就这么定|准了|OK|ok",
        RegexOption.IGNORE_CASE,
    )

    private val BARE_AGREEMENT = Regex("^(好|行|中|嗯+|成)[！!。.，,\\s~～]*$")

    /** 明确的拒绝/否定：命中即视为没有同意（"不可以" 里虽含 "可以"，但不能算答应）。 */
    private val REFUSAL = Regex(
        "不(好|行|成|要|去|可以|方便|愿意|想)|算了|不用|别|取消|没空|去不了|改天|下次吧|不行",
    )
}
