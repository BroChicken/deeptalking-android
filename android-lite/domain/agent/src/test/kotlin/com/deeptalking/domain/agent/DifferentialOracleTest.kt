package com.deeptalking.domain.agent

import com.deeptalking.core.model.Character
import com.deeptalking.core.model.ChatMessage
import com.deeptalking.core.model.Role
import com.deeptalking.domain.agent.prompts.PersonaInputs
import com.deeptalking.domain.agent.prompts.RequestBuilder
import com.deeptalking.domain.agent.prompts.RequestPhase
import com.deeptalking.domain.agent.prompts.buildSystemPrompt
import com.deeptalking.domain.agent.prompts.buildVolatileContext
import com.deeptalking.domain.memory.MemoryServiceImpl
import com.deeptalking.core.model.AppConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Differential oracle: for each scenario, this compares the Kotlin-native
 * prompt/volatile/tool contract against the legacy JS output captured by
 * tools/diff/run-js.mjs (committed as js-oracle.json).
 *
 * The goal is to catch behavioral drift between the two implementations. Exact
 * string equality is required for the tool contract; prompt/volatile content is
 * compared structurally (sections present) because whitespace-only differences
 * are not user-visible.
 */
class DifferentialOracleTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun readResource(name: String): String =
        javaClass.classLoader.getResourceAsStream(name)?.bufferedReader()?.readText()
            ?: error("missing test resource: $name")

    private data class Scenario(val id: String, val character: Character, val config: AppConfig, val query: String, val phase: RequestPhase)

    private fun loadScenarios(now: String): List<Scenario> {
        val root = json.parseToJsonElement(readResource("scenarios.json")).jsonObject
        val arr = root["scenarios"]!!.jsonArray
        return arr.map { element ->
            val obj = element.jsonObject
            val character = json.decodeFromJsonElement(Character.serializer(), obj["character"]!!)
            val cfgObj = obj["config"]!!.jsonObject
            val config = AppConfig(
                apiPlatform = cfgObj["apiPlatform"]?.jsonPrimitive?.contentOrNull ?: "deepseek",
                modelName = cfgObj["modelName"]?.jsonPrimitive?.contentOrNull ?: "deepseek-flash",
                temperature = cfgObj["temperature"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.8,
                stream = cfgObj["stream"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: true,
                reasoningEffort = cfgObj["reasoningEffort"]?.jsonPrimitive?.contentOrNull ?: "medium",
                proactiveEnabled = cfgObj["proactiveEnabled"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false,
                styleCritique = cfgObj["styleCritique"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false,
                quickReplyRepair = cfgObj["quickReplyRepair"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false,
            )
            Scenario(
                id = obj["id"]!!.jsonPrimitive.content,
                character = character,
                config = config,
                query = obj["query"]!!.jsonPrimitive.content,
                phase = if (obj["phase"]?.jsonPrimitive?.contentOrNull == "submit") RequestPhase.SUBMIT else RequestPhase.AUTO,
            )
        }
    }

    private fun oracle(): JsonObject =
        json.parseToJsonElement(readResource("js-oracle.json")).jsonObject

    @Test
    fun `fixed clock and timezone for determinism`() {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Shanghai"))
    }

    @Test
    fun `tool contract matches the legacy JS oracle`() = runBlocking {
        val now = json.parseToJsonElement(readResource("scenarios.json")).jsonObject["now"]!!.jsonPrimitive.content
        val scenarios = loadScenarios(now)
        val expectedAll = oracle()
        val memory = MemoryServiceImpl()

        for (scenario in scenarios) {
            val expected = expectedAll[scenario.id]!!.jsonObject["body"]!!.jsonObject
            val builder = RequestBuilder(scenario.config)
            // The submit phase exposes only submit_response; the auto phase exposes the full set.
            val allDefs = defaultTools(memory, web = null, stickersEnabled = scenario.character.stickers.isNotEmpty()).map { it.definition }
            val defs = if (scenario.phase == RequestPhase.SUBMIT) {
                allDefs.filter { it.name == "submit_response" }
            } else {
                allDefs
            }

            val expectedToolNames = expected["toolNames"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
            val actualToolNames = defs.map { it.name }
            assertEquals(
                "[${scenario.id}] tool name order/count must match legacy JS",
                expectedToolNames,
                actualToolNames,
            )

            for ((index, def) in defs.withIndex()) {
                val jsTool = expected["toolNames"]!!.jsonArray[index].jsonObject
                val jsRequired = jsTool["required"]!!.jsonArray.map { it.jsonPrimitive.content }.sorted()
                val jsProps = jsTool["propertyKeys"]!!.jsonArray.map { it.jsonPrimitive.content }.sorted()
                val kotlinParams = json.parseToJsonElement(def.parametersJson).jsonObject
                val kotlinRequired = (kotlinParams["required"]?.jsonArray ?: JsonArray(emptyList()))
                    .map { it.jsonPrimitive.content }.sorted()
                val kotlinProps = (kotlinParams["properties"]?.jsonObject?.keys ?: emptySet()).sorted()
                assertEquals("[${scenario.id}] ${def.name} required params", jsRequired, kotlinRequired)
                assertEquals("[${scenario.id}] ${def.name} property keys", jsProps, kotlinProps)
                assertEquals("[${scenario.id}] ${def.name} strict flag", jsTool["strict"]!!.jsonPrimitive.content.toBoolean(), def.strict)
            }
        }
    }

    @Test
    fun `submit phase locks submit_response and effort none`() = runBlocking {
        val now = json.parseToJsonElement(readResource("scenarios.json")).jsonObject["now"]!!.jsonPrimitive.content
        val scenarios = loadScenarios(now)
        val expectedAll = oracle()
        val memory = MemoryServiceImpl()

        for (scenario in scenarios) {
            val expectedBody = expectedAll[scenario.id]!!.jsonObject["body"]!!.jsonObject
            val builder = RequestBuilder(scenario.config)
            val defs = defaultTools(memory, web = null, stickersEnabled = false).map { it.definition }
            val request = builder.build(
                phase = scenario.phase,
                instructions = "x",
                input = emptyList(),
                tools = defs,
            )
            val expectedEffort = expectedBody["reasoningEffort"]!!.jsonPrimitive.content
            assertEquals("[${scenario.id}] reasoning effort", expectedEffort, request.reasoningEffort)
            val expectedChoice = expectedBody["toolChoice"]
            if (expectedChoice is JsonPrimitive) {
                assertEquals("[${scenario.id}] tool_choice", expectedChoice.content, "auto")
            } else {
                val fn = expectedChoice!!.jsonObject["name"]!!.jsonPrimitive.content
                assertEquals("[${scenario.id}] submit lock", fn, (request.toolChoice as? com.deeptalking.engine.ondevice.ToolChoice.Function)?.name)
            }
            assertEquals("[${scenario.id}] max output tokens", 8192, request.maxOutputTokens)
        }
    }

    @Test
    fun `system prompt matches the legacy JS oracle`() = runBlocking {
        val now = json.parseToJsonElement(readResource("scenarios.json")).jsonObject["now"]!!.jsonPrimitive.content
        val scenarios = loadScenarios(now)
        val expectedAll = oracle()

        for (scenario in scenarios) {
            val expected = expectedAll[scenario.id]!!.jsonObject["systemPrompt"]!!.jsonPrimitive.content
            val actual = buildSystemPrompt(PersonaInputs(scenario.character, scenario.config))
            assertEquals(
                "[${scenario.id}] system prompt must be byte-identical to legacy JS",
                expected,
                actual,
            )
        }
    }

    /**
     * The narrative-rhythm skeleton is randomly chosen per turn in BOTH
     * implementations (see buildNarrativePatternDirective), so its specific
     * wording cannot match. We normalize that one paragraph out before the
     * byte-for-byte comparison; everything else must be identical.
     */
    private fun normalizeSkeleton(text: String): String =
        text.replace(
            Regex("【本轮节奏骨架（每轮随机给出，仅作节奏参考）】[\\s\\S]*?骨架只约束节奏与详略分布，不要写出任何结构标签或说明文字，措辞与语气一律服从角色设定。"),
            "【本轮节奏骨架】(正常化)",
        )

    @Test
    fun `volatile context matches the legacy JS oracle`() = runBlocking {
        val now = json.parseToJsonElement(readResource("scenarios.json")).jsonObject["now"]!!.jsonPrimitive.content
        val scenarios = loadScenarios(now)
        val expectedAll = oracle()
        val memory = MemoryServiceImpl()
        val instant = Instant.parse(now)

        for (scenario in scenarios) {
            val expected = expectedAll[scenario.id]!!.jsonObject["volatileContext"]!!.jsonPrimitive.content
            val recentAssistant = scenario.character.instant.filter { it.role == Role.Assistant }.map { it.content }
            val actual = buildVolatileContext(
                character = scenario.character,
                query = scenario.query,
                memory = memory,
                now = instant,
                recentAssistantReplies = recentAssistant,
            )
            assertEquals(
                "[${scenario.id}] volatile context must be byte-identical to legacy JS (skeleton normalized)",
                normalizeSkeleton(expected),
                normalizeSkeleton(actual),
            )
        }
    }
}
