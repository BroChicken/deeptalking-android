package com.deeptalking.lite

/**
 * Context needed to retry only the turn worker after its automatic retries failed.
 * The assistant reply is already in the chat and is never regenerated.
 */
data class WorkerFailureUi(
    val characterId: String,
    val userText: String,
    val reply: String,
    /** Synthetic id used by the worker for `current_response` evidence. */
    val replyId: String,
    /** Real `messages` row id of the assistant reply this failure belongs to. */
    val assistantRowId: String,
    val proactive: Boolean,
    val reason: String,
    val showDialog: Boolean = true,
)
