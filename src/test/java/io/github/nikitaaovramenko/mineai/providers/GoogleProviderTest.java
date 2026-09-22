package io.github.nikitaaovramenko.mineai.providers;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletionException;

import org.junit.After;
import org.junit.Test;

import com.sun.net.httpserver.HttpServer;

import static org.junit.Assert.*;

// The real LangChain4j Gemini model against a local stand-in for the Gemini API, so the parts that differ
// from Anthropic are checked without a key or a network: the blocking-only model call, the thought
// signature round trip, and Google's error shapes.
public class GoogleProviderTest {
    private record Reply(int status, String body) {}

    private final Deque<Reply> replies = new ArrayDeque<>();
    private final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    private final HttpServer server;

    public GoogleProviderTest() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            Reply reply = replies.remove();
            byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.status(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @After
    public void stopServer() {
        server.stop(0);
    }

    private String ask(String prompt) {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta";
        return LangChain4jClient.ask(AiProvider.GOOGLE,
                () -> LangChain4jClient.google("test-key", "gemini-test", baseUrl),
                prompt, List.of(new LangChain4jClientTest.SeedTools()), Runnable::run).join();
    }

    private static String candidate(String part) {
        return "{\"candidates\": [{\"content\": {\"role\": \"model\", \"parts\": [" + part + "]},"
                + " \"finishReason\": \"STOP\"}],"
                + " \"usageMetadata\": {\"promptTokenCount\": 1, \"candidatesTokenCount\": 1, \"totalTokenCount\": 2}}";
    }

    @Test
    public void sendsTheThoughtSignatureBackWithTheToolResult() {
        replies.add(new Reply(200, candidate("{\"functionCall\": {\"name\": \"getWorldSeed\", \"args\": {}},"
                + " \"thoughtSignature\": \"c2lnbmF0dXJl\"}")));
        replies.add(new Reply(200, candidate("{\"text\": \"The seed is 42.\"}")));

        assertEquals("The seed is 42.", ask("What is the seed?"));
        String followUp = requests.get(1);
        assertTrue(followUp, followUp.contains("c2lnbmF0dXJl"));
        assertTrue(followUp, followUp.contains("functionResponse"));
    }

    @Test
    public void reportsABadKeyLikeThePlainClients() {
        replies.add(new Reply(400, "{\"error\": {\"code\": 400, \"message\": \"API key not valid.\","
                + " \"status\": \"INVALID_ARGUMENT\", \"details\": [{\"reason\": \"API_KEY_INVALID\"}]}}"));

        var error = assertThrows(CompletionException.class, () -> ask("Hi"));
        assertTrue(String.valueOf(error.getCause()), error.getCause() instanceof RequestException);
        assertEquals("Google AI Studio rejected the API key. Check googleApiKey in the config.",
                error.getCause().getMessage());
    }
}
