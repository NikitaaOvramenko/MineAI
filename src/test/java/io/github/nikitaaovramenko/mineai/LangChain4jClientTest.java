package io.github.nikitaaovramenko.mineai;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.junit.Test;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;

import static org.junit.Assert.*;

public class LangChain4jClientTest {
    // Plays back canned replies and remembers every request it was sent.
    static class ScriptedModel implements ChatModel {
        final List<ChatRequest> requests = new ArrayList<>();
        private final Deque<ChatResponse> replies;

        ScriptedModel(ChatResponse... replies) {
            this.replies = new ArrayDeque<>(List.of(replies));
        }

        @Override
        public CompletableFuture<ChatResponse> doChatAsync(ChatRequest request) {
            requests.add(request);
            return CompletableFuture.completedFuture(replies.remove());
        }
    }

    static class SeedTools {
        int calls;

        @Tool("Returns the world seed.")
        long getWorldSeed() {
            calls++;
            return 42;
        }
    }

    private static ChatResponse seedCall(FinishReason finishReason) {
        var call = ToolExecutionRequest.builder().id("call-1").name("getWorldSeed").arguments("{}").build();
        return ChatResponse.builder().aiMessage(AiMessage.from(call)).finishReason(finishReason).build();
    }

    private static ChatResponse answer(String text) {
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).finishReason(FinishReason.STOP).build();
    }

    private static CompletableFuture<String> converse(ChatModel model, SeedTools tools) {
        List<ChatMessage> messages = new ArrayList<>(List.of(UserMessage.from("What is the seed?")));
        return LangChain4jClient.converse(model, messages, new LangChain4jTools(List.of(tools), Runnable::run), 3);
    }

    @Test
    public void offersTheToolsToTheModel() {
        var model = new ScriptedModel(answer("Hi!"));
        assertEquals("Hi!", converse(model, new SeedTools()).join());
        assertEquals("getWorldSeed", model.requests.get(0).toolSpecifications().get(0).name());
    }

    @Test
    public void sendsToolResultsBackUntilClaudeAnswers() {
        var model = new ScriptedModel(seedCall(FinishReason.TOOL_EXECUTION), answer("The seed is 42."));
        var tools = new SeedTools();
        assertEquals("The seed is 42.", converse(model, tools).join());
        assertEquals(1, tools.calls);

        List<ChatMessage> followUp = model.requests.get(1).messages();
        assertTrue(followUp.get(1) instanceof AiMessage call && call.hasToolExecutionRequests());
        var result = (ToolExecutionResultMessage) followUp.get(2);
        assertEquals("call-1", result.id());
        assertEquals("42", result.text());
    }

    @Test
    public void givesUpWhenClaudeKeepsCallingTools() {
        var model = new ScriptedModel(seedCall(FinishReason.TOOL_EXECUTION), seedCall(FinishReason.TOOL_EXECUTION),
                seedCall(FinishReason.TOOL_EXECUTION), seedCall(FinishReason.TOOL_EXECUTION));
        var error = assertThrows(CompletionException.class, () -> converse(model, new SeedTools()).join());
        assertTrue(error.getCause() instanceof RequestException);
    }

    @Test
    public void neverRunsToolsFromATruncatedTurn() {
        var tools = new SeedTools();
        var error = assertThrows(CompletionException.class,
                () -> converse(new ScriptedModel(seedCall(FinishReason.LENGTH)), tools).join());
        assertTrue(error.getCause() instanceof RequestException);
        assertEquals(0, tools.calls);
    }
}
