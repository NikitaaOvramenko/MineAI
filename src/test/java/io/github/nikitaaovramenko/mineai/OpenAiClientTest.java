package io.github.nikitaaovramenko.mineai;

import org.junit.Test;

import static org.junit.Assert.*;

public class OpenAiClientTest {
    @Test
    public void readsAllTextAfterReasoningItems() {
        assertEquals("Hello\nworld", OpenAiClient.parseResponse(200, """
                {"output":[{"type":"reasoning"},{"type":"message","content":[
                  {"type":"output_text","text":"Hello"},
                  {"type":"output_text","text":"world"}]}]}
                """));
    }

    @Test
    public void showsRefusals() {
        assertEquals("Cannot help.", OpenAiClient.parseResponse(200, """
                {"output":[{"type":"message","content":[{"type":"refusal","refusal":"Cannot help."}]}]}
                """));
    }

    @Test
    public void marksTruncatedAnswers() {
        assertTrue(OpenAiClient.parseResponse(200, """
                {"status":"incomplete","output":[{"type":"message","content":[{"type":"output_text","text":"Hello"}]}]}
                """).contains("output limit"));
    }

    @Test
    public void rejectsEmptyAndMalformedResponses() {
        for (String body : new String[] {"{}", "not json", "{\"output\":[]}"}) {
            assertThrows(RequestException.class, () -> OpenAiClient.parseResponse(200, body));
        }
    }

    @Test
    public void errorsDoNotExposeResponseBodies() {
        for (int status : new int[] {400, 401, 403, 404, 429, 500}) {
            var error = assertThrows(RequestException.class,
                    () -> OpenAiClient.parseResponse(status, "secret-api-key"));
            assertFalse(error.getMessage().contains("secret-api-key"));
        }
    }
}
