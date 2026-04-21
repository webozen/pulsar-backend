package com.pulsar.content.api;

import com.pulsar.content.service.AnythingLlmClient;
import com.pulsar.kernel.security.RequireModule;
import com.pulsar.kernel.tenant.TenantContext;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/content/chat")
@RequireModule("content")
public class ContentChatController {
    private final AnythingLlmClient llm;

    public ContentChatController(AnythingLlmClient llm) {
        this.llm = llm;
    }

    public record ChatRequest(@NotBlank String message) {}

    @PostMapping
    public Map<String, Object> chat(@RequestBody ChatRequest req) {
        String slug = TenantContext.require().slug();
        String response = llm.chat(slug, req.message());
        return Map.of("response", response);
    }
}
