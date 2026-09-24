package io.github.nikitaaovramenko.mineai.providers;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.ToolExecutionResultMessage;

// The tool objects of one /ai request as LangChain4jClient sees them: specifications for the model,
// and the model's calls run against them. Minecraft-free on purpose, like the clients.
final class LangChain4jTools {
    private static final Logger LOGGER = LoggerFactory.getLogger(LangChain4jTools.class);
    private static final Gson GSON = new Gson();
    // A stopping server drops its queued tasks, and a call that never runs would otherwise leave the
    // player's request pending for good.
    private static final long TOOL_TIMEOUT_SECONDS = 30;

    private record Binding(Object target, Method method) {}

    private final Executor executor;
    private final List<ToolSpecification> specifications = new ArrayList<>();
    private final Map<String, Binding> bindings = new HashMap<>();

    LangChain4jTools(List<?> tools, Executor executor) {
        this.executor = executor;
        for (Object tool : tools) {
            for (Method method : tool.getClass().getDeclaredMethods()) {
                if (!method.isAnnotationPresent(Tool.class)) {
                    continue;
                }
                ToolSpecification specification = ToolSpecifications.toolSpecificationFrom(method);
                if (bindings.putIfAbsent(specification.name(), new Binding(tool, method)) != null) {
                    throw new IllegalArgumentException("Two tools are named " + specification.name());
                }
                method.setAccessible(true);
                specifications.add(specification);
            }
        }
    }

    List<ToolSpecification> specifications() {
        return specifications;
    }

    // Runs one turn's calls in order, as a single task on the executor (the server thread in game).
    CompletableFuture<List<ToolExecutionResultMessage>> executeAll(List<ToolExecutionRequest> requests) {
        return CompletableFuture.supplyAsync(() -> requests.stream().map(this::execute).toList(), executor)
                .orTimeout(TOOL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    // A failing call becomes an error result rather than an exception, so the model can explain it
    // or try something else.
    ToolExecutionResultMessage execute(ToolExecutionRequest request) {
        String text;
        boolean failed = false;
        try {
            LOGGER.info("AI called tool '{}' with arguments: {}", request.name(), request.arguments());
            Binding binding = bindings.get(request.name());
            if (binding == null) {
                throw new IllegalArgumentException("There is no tool named " + request.name() + ".");
            }
            Method method = binding.method();
            Object result = method.invoke(binding.target(), arguments(method, request.arguments()));
            text = method.getReturnType() == void.class ? "Done."
                    : result instanceof String string ? string : GSON.toJson(result);
            LOGGER.info("AI tool '{}' completed successfully", request.name());
        } catch (ReflectiveOperationException | RuntimeException exception) {
            Throwable cause = exception instanceof InvocationTargetException ? exception.getCause() : exception;
            LOGGER.warn("Tool {} failed", request.name(), cause);
            text = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
            failed = true;
        }
        return ToolExecutionResultMessage.builder()
                .id(request.id())
                .toolName(request.name())
                .text(text)
                .isError(failed)
                .build();
    }

    private static Object[] arguments(Method method, String json) {
        JsonObject arguments = json == null || json.isBlank()
                ? new JsonObject()
                : JsonParser.parseString(json).getAsJsonObject();
        Parameter[] parameters = method.getParameters();
        Object[] values = new Object[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            values[i] = argument(parameters[i], arguments);
        }
        return values;
    }

    private static Object argument(Parameter parameter, JsonObject arguments) {
        P annotation = parameter.getAnnotation(P.class);
        // The naming rule ToolSpecifications uses, so these match the names the model was shown.
        String name = annotation != null && !annotation.name().isBlank() ? annotation.name() : parameter.getName();
        JsonElement value = arguments.get(name);
        if ((value == null || value.isJsonNull()) && annotation != null
                && !P.NO_DEFAULT.equals(annotation.defaultValue())) {
            value = parameter.getType() == String.class
                    ? new JsonPrimitive(annotation.defaultValue())
                    : JsonParser.parseString(annotation.defaultValue());
        }
        if (value == null || value.isJsonNull()) {
            if (parameter.getType().isPrimitive()) {
                throw new IllegalArgumentException("Missing argument " + name + ".");
            }
            return null;
        }
        return GSON.fromJson(value, parameter.getParameterizedType());
    }
}
