package com.deeptalking.domain.agent.tools

import com.deeptalking.core.model.Character
import com.deeptalking.core.model.GroupMember
import com.deeptalking.core.model.MemorySubject

/** Helpers shared by the memory tools (port of resolveMemoryHost / actorLabel). */
internal object MemoryToolSupport {

    fun resolveMember(character: Character, memberName: String): GroupMember? {
        val name = memberName.trim()
        if (name.isEmpty()) return null
        return character.members.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }

    /** Mirrors the legacy member store view used by search/list/delete. */
    fun memberAsCharacter(character: Character, member: GroupMember): Character = Character(
        id = member.id,
        name = member.name,
        emoji = member.emoji,
        staticProfile = member.staticProfile,
        dynamicState = member.dynamicState,
        shortTerm = member.shortTerm,
        longTerm = member.longTerm,
        lorebook = member.lorebook,
    )

    fun actorLabel(character: Character, subject: MemorySubject?): String = when (subject) {
        MemorySubject.User -> character.staticProfile.userAddress.replace(Regex("\\s+"), "").ifBlank { "对方" }
        MemorySubject.Character -> character.name.ifBlank { "角色本人" }
        MemorySubject.Relationship -> "你们"
        MemorySubject.World -> "背景"
        else -> "相关记忆"
    }
}
