package com.pulsar.content.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import com.pulsar.content.service.AnythingLlmClient;
import com.pulsar.kernel.tenant.TenantContext;
import com.pulsar.kernel.tenant.TenantInfo;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** Direct-instantiation tests for ContentChatController. */
class ContentChatControllerTest {

    private AnythingLlmClient llm;
    private ContentChatController controller;

    @BeforeEach
    void setUp() {
        llm = Mockito.mock(AnythingLlmClient.class);
        controller = new ContentChatController(llm);
        TenantContext.set(new TenantInfo(
            1L, "acme", "Acme", "acme_db", Set.of("content"), false));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void chat_delegatesToLlmWithTenantSlug() {
        when(llm.chat("acme", "hi")).thenReturn("hello back");

        Map<String, Object> resp = controller.chat(
            new ContentChatController.ChatRequest("hi"));

        assertEquals("hello back", resp.get("response"));
    }
}
