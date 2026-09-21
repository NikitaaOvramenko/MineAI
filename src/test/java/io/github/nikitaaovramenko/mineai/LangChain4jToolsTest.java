package io.github.nikitaaovramenko.mineai;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.Test;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ToolExecutionResultMessage;

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

    private static LangChain4jTools tools() {
        return new LangChain4jTools(List.of(new SampleTools()), Runnable::run);
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
}
