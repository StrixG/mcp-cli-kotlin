import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class ToolBridgeTest {

    @Test
    fun parametersWrapPropertiesWithTypeObjectAndRequired() {
        val props = buildJsonObject {
            put("entity_id", buildJsonObject { put("type", "string") })
        }
        val params = toolParameters(props, listOf("entity_id"))
        assertEquals("object", params["type"]!!.jsonPrimitive.content)
        assertTrue(params["properties"]!!.jsonObject.containsKey("entity_id"))
        assertEquals("entity_id", params["required"]!!.jsonArray.single().jsonPrimitive.content)
    }

    @Test
    fun requiredKeyOmittedWhenEmpty() {
        val params = toolParameters(JsonObject(emptyMap()), emptyList())
        assertTrue(!params.containsKey("required"), params.toString())
    }

    @Test
    fun parseToolArgsReadsObject() {
        val obj = parseToolArgs("""{"entity_id":"light.kitchen"}""")
        assertEquals("light.kitchen", obj["entity_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun parseToolArgsReturnsEmptyOnGarbage() {
        assertTrue(parseToolArgs("not json").isEmpty())
        assertTrue(parseToolArgs("").isEmpty())
    }
}
