package io.github.nikitaaovramenko.mineai.blueprint;

import java.util.List;

import org.junit.Test;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.model.chat.request.json.JsonAnyOfSchema;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import static org.junit.Assert.*;

// What the model is shown for a Blueprint parameter. Renaming a record or dropping an optional marker
// changes the model's instructions, so these break loudly.
public class BlueprintSchemaTest {
    static class Tools {
        @Tool("Plans a building.")
        String plan(Blueprint blueprint) {
            return "";
        }
    }

    private static List<JsonObjectSchema> operations() {
        var plan = ToolSpecifications.toolSpecificationsFrom(new Tools()).get(0);
        var blueprint = (JsonObjectSchema) plan.parameters().properties().get("blueprint");
        var operations = (JsonArraySchema) blueprint.properties().get("operations");
        return ((JsonAnyOfSchema) operations.items()).anyOf().stream().map(JsonObjectSchema.class::cast).toList();
    }

    @Test
    public void offersEachOperationAsAChoiceNamedByType() {
        assertEquals(List.of("Fill", "Walls", "Clear", "Door", "GableRoof"), operations().stream()
                .map(operation -> ((JsonEnumSchema) operation.properties().get("type")).enumValues().get(0))
                .toList());
    }

    @Test
    public void leavesTheRoofSettingsOptional() {
        assertEquals(List.of("type", "from", "to", "stairs"), operations().get(4).required());
    }
}
