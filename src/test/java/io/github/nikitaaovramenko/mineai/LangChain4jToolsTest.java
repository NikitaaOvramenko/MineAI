package io.github.nikitaaovramenko.mineai;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.Test;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.request.json.JsonAnyOfSchema;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import static org.junit.Assert.*;

public class LangChain4jToolsTest {
    static class SampleTools {
        @Tool("Adds two numbers.")
        int add(int a, int b) {
            return a + b;
        }

        @Tool("Greets someone.")
        String greet(@P(name = "who", description = "Who to greet", defaultValue = "Steve") String name) {
            return "Hello " + name;
        }

        @Tool("Always fails.")
        void explode() {
            throw new IllegalStateException("The creeper exploded.");
        }
    }

    sealed interface Shape permits Circle, Square {}

    record Circle(int radius) implements Shape {}

    record Square(int side) implements Shape {}

    enum Speed { SLOW, FAST }

    static class ShapeTools {
        @Tool("Describes shapes.")
        String describe(List<Shape> shapes) {
            return shapes.toString();
        }

        @Tool("Makes shapes.")
        List<Shape> make() {
            return List.of(new Circle(2), new Square(3));
        }

        @Tool("Moves.")
        String move(@P(value = "How fast", defaultValue = "SLOW") Speed speed) {
            return speed.name();
        }
    }

    private static LangChain4jTools tools() {
        return new LangChain4jTools(List.of(new SampleTools()), Runnable::run);
    }

    private static LangChain4jTools shapeTools() {
        return new LangChain4jTools(List.of(new ShapeTools()), Runnable::run);
    }

    private static ToolExecutionRequest call(String name, String arguments) {
        return ToolExecutionRequest.builder().id("call-" + name).name(name).arguments(arguments).build();
    }

    private static boolean failed(ToolExecutionResultMessage result) {
        return Boolean.TRUE.equals(result.isError());
    }

    @Test
    public void describesToolsWithTheirParameterNames() {
        var add = tools().specifications().stream()
                .filter(specification -> specification.name().equals("add"))
                .findFirst().orElseThrow();
        assertEquals("Adds two numbers.", add.description());
        // Real names rather than arg0/arg1 need javac's -parameters flag, set in build.gradle.
        assertEquals(Set.of("a", "b"), add.parameters().properties().keySet());
    }

    @Test
    public void bindsArgumentsByName() {
        var result = tools().execute(call("add", "{\"b\": 3, \"a\": 2}"));
        assertFalse(failed(result));
        assertEquals("call-add", result.id());
        assertEquals("5", result.text());
    }

    @Test
    public void fillsInDefaultsForOmittedArguments() {
        assertEquals("Hello Steve", tools().execute(call("greet", "{}")).text());
        assertEquals("Hello Alex", tools().execute(call("greet", "{\"who\": \"Alex\"}")).text());
    }

    @Test
    public void reportsFailuresToTheModelInsteadOfThrowing() {
        var exploded = tools().execute(call("explode", "{}"));
        assertTrue(failed(exploded));
        assertEquals("The creeper exploded.", exploded.text());

        assertTrue(failed(tools().execute(call("add", "{\"a\": 2}"))));
        assertTrue(failed(tools().execute(call("add", "not json"))));
        assertTrue(failed(tools().execute(call("fly", "{}"))));
    }

    @Test
    public void runsATurnsCallsTogetherOnTheGivenExecutor() {
        List<Runnable> queued = new ArrayList<>();
        var tools = new LangChain4jTools(List.of(new SampleTools()), queued::add);
        var results = tools.executeAll(List.of(call("add", "{\"a\": 1, \"b\": 1}"), call("greet", "{}")));
        assertFalse(results.isDone());
        assertEquals(1, queued.size());

        queued.get(0).run();
        assertEquals(List.of("2", "Hello Steve"),
                results.join().stream().map(ToolExecutionResultMessage::text).toList());
    }

    @Test
    public void rejectsDuplicateToolNames() {
        assertThrows(IllegalArgumentException.class,
                () -> new LangChain4jTools(List.of(new SampleTools(), new SampleTools()), Runnable::run));
    }

    @Test
    public void describesSealedTypesAsAChoiceOfTaggedObjects() {
        var describe = shapeTools().specifications().stream()
                .filter(specification -> specification.name().equals("describe"))
                .findFirst().orElseThrow();
        var shapes = (JsonArraySchema) describe.parameters().properties().get("shapes");
        var options = ((JsonAnyOfSchema) shapes.items()).anyOf();
        assertEquals(2, options.size());
        var circle = (JsonObjectSchema) options.get(0);
        assertEquals(List.of("Circle"), ((JsonEnumSchema) circle.properties().get("type")).enumValues());
        assertTrue(circle.properties().containsKey("radius"));
    }

    @Test
    public void bindsSealedTypesByTheirTypeProperty() {
        var result = shapeTools().execute(call("describe",
                "{\"shapes\": [{\"type\": \"Circle\", \"radius\": 2}, {\"type\": \"Square\", \"side\": 3}]}"));
        assertFalse(failed(result));
        assertEquals("[Circle[radius=2], Square[side=3]]", result.text());
    }

    @Test
    public void writesTheTypePropertyBackSoResultsCanBeSentAgain() {
        String made = shapeTools().execute(call("make", "{}")).text();
        assertEquals("[{\"type\":\"Circle\",\"radius\":2},{\"type\":\"Square\",\"side\":3}]", made);
        assertEquals("[Circle[radius=2], Square[side=3]]",
                shapeTools().execute(call("describe", "{\"shapes\": " + made + "}")).text());
    }

    @Test
    public void namesTheValidTypesWhenOneIsUnknown() {
        var result = shapeTools().execute(call("describe", "{\"shapes\": [{\"type\": \"Triangle\"}]}"));
        assertTrue(failed(result));
        assertTrue(result.text(), result.text().contains("Circle, Square"));
    }

    @Test
    public void fillsInEnumDefaults() {
        assertEquals("SLOW", shapeTools().execute(call("move", "{}")).text());
        assertEquals("FAST", shapeTools().execute(call("move", "{\"speed\": \"FAST\"}")).text());
    }
}
