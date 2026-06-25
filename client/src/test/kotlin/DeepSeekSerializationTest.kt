import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeepSeekSerializationTest {

    @Test
    fun requestKeepsModelAndOmitsNullFields() {
        val req = ChatRequest(
            model = "deepseek-v4-pro",
            messages = listOf(ChatMessage(role = "user", content = "hi")),
        )
        val s = DeepSeekJson.encodeToString(ChatRequest.serializer(), req)
        assertTrue(s.contains("\"model\":\"deepseek-v4-pro\""), s)
        assertTrue(!s.contains("\"tool_calls\""), "null tool_calls must be omitted: $s")
        assertTrue(!s.contains("\"temperature\""), "null temperature must be omitted: $s")
    }

    @Test
    fun responseParsesToolCallsAndIgnoresReasoningContent() {
        val json = """
            {"choices":[{"finish_reason":"tool_calls","message":{
              "role":"assistant","content":null,"reasoning_content":"thinking...",
              "tool_calls":[{"id":"c1","type":"function",
                "function":{"name":"get_state","arguments":"{\"entity_id\":\"light.kitchen\"}"}}]}}]}
        """.trimIndent()
        val resp = DeepSeekJson.decodeFromString(ChatResponse.serializer(), json)
        val msg = resp.choices.single().message
        assertEquals("get_state", msg.toolCalls!!.single().function.name)
        assertEquals("c1", msg.toolCalls!!.single().id)
        assertEquals("{\"entity_id\":\"light.kitchen\"}", msg.toolCalls!!.single().function.arguments)
    }
}
