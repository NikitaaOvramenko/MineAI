package io.github.nikitaaovramenko.mineai;

import java.util.Optional;

import org.junit.Test;

import static org.junit.Assert.*;

public class AiProviderTest {
    @Test
    public void readsConfiguredIdsLeniently() {
        assertEquals(Optional.of(AiProvider.OPENAI), AiProvider.byId("openai"));
        assertEquals(Optional.of(AiProvider.ANTHROPIC), AiProvider.byId("  Anthropic "));
    }

    @Test
    public void rejectsUnknownIds() {
        for (String id : new String[] {"", "claude", "gpt", null}) {
            assertEquals(Optional.empty(), AiProvider.byId(id));
        }
    }

    @Test
    public void listsEveryProviderForConfigComments() {
        for (AiProvider provider : AiProvider.values()) {
            assertTrue(AiProvider.ids().contains(provider.id()));
        }
    }
}
