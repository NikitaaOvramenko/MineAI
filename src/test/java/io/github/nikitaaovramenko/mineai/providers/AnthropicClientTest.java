package io.github.nikitaaovramenko.mineai.providers;

import org.junit.Test;

import static org.junit.Assert.*;

public class AnthropicClientTest {
    @Test
    public void readsAllTextBlocksPastThinking() {
        assertEquals("Hello\nworld", AnthropicClient.parseResponse(200, """
                {"stop_reason":"end_turn","content":[{"type":"thinking","thinking":""},
                  {"type":"text","text":"Hello"},
                  {"type":"text","text":"world"}]}
                """));
    }

    @Test
    public void marksTruncatedAnswers() {
        assertTrue(AnthropicClient.parseResponse(200, """
                {"stop_reason":"max_tokens","content":[{"type":"text","text":"Hello"}]}
                """).contains("output limit"));
    }

    @Test
    public void explainsEmptyAnswersByStopReason() {
        var refused = assertThrows(RequestException.class, () -> AnthropicClient.parseResponse(200, """
                {"stop_reason":"refusal","stop_details":{"type":"refusal","category":"cyber"},"content":[]}
                """));
        assertTrue(refused.getMessage().contains("declined"));

        var exhausted = assertThrows(RequestException.class, () -> AnthropicClient.parseResponse(200, """
                {"stop_reason":"max_tokens","content":[{"type":"thinking","thinking":""}]}
                """));
        assertTrue(exhausted.getMessage().contains("output budget"));
    }

    @Test
    public void rejectsEmptyAndMalformedResponses() {
        for (String body : new String[] {"{}", "not json", "{\"content\":[]}"}) {
            assertThrows(RequestException.class, () -> AnthropicClient.parseResponse(200, body));
        }
    }

    @Test
    public void errorsDoNotExposeResponseBodies() {
        for (int status : new int[] {400, 401, 403, 404, 429, 500, 529}) {
            var error = assertThrows(RequestException.class,
                    () -> AnthropicClient.parseResponse(status, "secret-api-key"));
            assertFalse(error.getMessage().contains("secret-api-key"));
        }
    }

    @Test
    public void errorsNameTheAnthropicConfigOptions() {
        var error = assertThrows(RequestException.class, () -> AnthropicClient.parseResponse(401, "{}"));
        assertTrue(error.getMessage().contains("anthropicApiKey"));
    }

    @Test
    public void keepsProviderErrorTextOutOfChatButInTheDetail() {
        var error = assertThrows(RequestException.class, () -> AnthropicClient.parseResponse(404, """
                {"type":"error","error":{"type":"not_found_error","message":"model: claude-nope"}}
                """));
        assertFalse(error.getMessage().contains("claude-nope"));
        assertTrue(error.detail().contains("not_found_error"));
        assertTrue(error.detail().contains("claude-nope"));
    }
}
