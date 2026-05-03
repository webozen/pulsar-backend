package com.pulsar.opendentalai.chat;

import com.pulsar.kernel.auth.Principal;
import com.pulsar.kernel.auth.PrincipalContext;
import com.pulsar.kernel.credentials.GeminiKeyResolver;
import com.pulsar.kernel.security.RequireModule;
import com.pulsar.kernel.tenant.TenantContext;
import com.pulsar.kernel.tenant.TenantDataSources;
import com.pulsar.opendentalai.schema.SchemaCatalog;
import com.pulsar.opendentalai.tools.RunQueryTool;
import com.pulsar.opendentalai.tools.RunQueryTool.Invocation;
import com.pulsar.opendentalai.tools.RunQueryTool.ToolResponse;
import com.pulsar.opendentalai.ws.SystemInstructions;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/opendental-ai")
@RequireModule("opendental-ai")
public class OpendentalAiChatController {
    private static final Logger log = LoggerFactory.getLogger(OpendentalAiChatController.class);

    private static final List<Map<String, Object>> FUNCTION_DECLARATIONS = List.of(Map.of(
        "name", "run_opendental_query",
        "description",
            "Run a read-only SELECT statement against the tenant's Open Dental MySQL database. " +
            "Use for any factual question about patients, appointments, production, scheduling, recalls, or billing.",
        "parameters", Map.of(
            "type", "object",
            "properties", Map.of(
                "sql", Map.of("type", "string", "description", "A single MySQL SELECT statement. No writes."),
                "explanation", Map.of("type", "string", "description", "One-sentence description of what this query computes.")
            ),
            "required", List.of("sql", "explanation")
        )
    ));

    private final TenantDataSources tenantDs;
    private final SchemaCatalog catalog;
    private final RunQueryTool runQueryTool;
    private final GeminiChatClient gemini;
    private final GeminiKeyResolver geminiKeyResolver;

    public OpendentalAiChatController(
        TenantDataSources tenantDs,
        SchemaCatalog catalog,
        RunQueryTool runQueryTool,
        GeminiChatClient gemini,
        GeminiKeyResolver geminiKeyResolver
    ) {
        this.tenantDs = tenantDs;
        this.catalog = catalog;
        this.runQueryTool = runQueryTool;
        this.gemini = gemini;
        this.geminiKeyResolver = geminiKeyResolver;
    }

    public record MessageDto(String role, String text) {}

    public record ChatRequest(
        @NotBlank String message,
        List<MessageDto> history
    ) {}

    public record ToolCallDto(String name, String sql, String explanation) {}
    public record ToolResultDto(String status, Integer rows, String error) {}
    public record ChatResponse(String reply, List<ToolCallDto> toolCalls, List<ToolResultDto> toolResults) {}

    @PostMapping("/chat")
    public ChatResponse chat(@Valid @RequestBody ChatRequest req) throws Exception {
        var tenant = TenantContext.require();
        DataSource ds = tenantDs.forDb(tenant.dbName());
        JdbcTemplate jdbc = new JdbcTemplate(ds);

        var rows = jdbc.queryForList(
            "SELECT od_developer_key, od_customer_key, timezone FROM opendental_ai_config WHERE id = 1"
        );
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "not_onboarded");
        }
        String odDev  = (String) rows.get(0).get("od_developer_key");
        String odCust = (String) rows.get(0).get("od_customer_key");
        String tz     = rows.get(0).get("timezone") instanceof String s ? s : "America/New_York";
        // Centralized resolver: tenant_credentials → legacy fallback (translate_config or opendental_ai_config) → platform default.
        String apiKey = geminiKeyResolver.resolveForDb(tenant.dbName()).apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FAILED_DEPENDENCY, "gemini_key_missing");
        }

        String systemInstruction = SystemInstructions.build(catalog, tenant.slug(), tz);

        // Build Gemini history from prior turns + new user message
        List<GeminiChatClient.Turn> history = new ArrayList<>();
        if (req.history() != null) {
            for (MessageDto m : req.history()) {
                history.add(new GeminiChatClient.Turn(m.role(), m.text()));
            }
        }
        history.add(new GeminiChatClient.Turn("user", req.message()));

        List<ToolCallDto> toolCalls = new ArrayList<>();
        List<ToolResultDto> toolResults = new ArrayList<>();

        // First Gemini call
        Map<String, Object> geminiResp = gemini.generate(apiKey, systemInstruction, history, FUNCTION_DECLARATIONS);
        List<Map<String, Object>> parts = extractParts(geminiResp);

        // Handle any function calls
        for (Map<String, Object> part : parts) {
            if (!part.containsKey("functionCall")) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> fc = (Map<String, Object>) part.get("functionCall");
            @SuppressWarnings("unchecked")
            Map<String, Object> args = fc.get("args") instanceof Map<?,?> m ? (Map<String, Object>) m : Map.of();
            String sql = args.get("sql") instanceof String s ? s : "";
            String explanation = args.get("explanation") instanceof String s ? s : "";

            log.info("Text chat tool call: tenant={} sql={}", tenant.slug(), sql.substring(0, Math.min(80, sql.length())));
            toolCalls.add(new ToolCallDto("run_opendental_query", sql, explanation));

                String userEmail = switch (PrincipalContext.get()) {
                case Principal.TenantUser tu -> tu.email();
                default -> tenant.slug() + "@pulsar.internal";
            };
            ToolResponse toolResp = runQueryTool.run(new Invocation(
                "chat-" + tenant.slug(), userEmail, req.message(),
                sql, odDev, odCust, ds
            ));

            Integer rowCount = null;
            if (toolResp.result() instanceof Map<?,?> r && r.get("count") instanceof Integer c) {
                rowCount = c;
            }
            toolResults.add(new ToolResultDto(toolResp.status(), rowCount, toolResp.error()));

            // Append model + tool result to history for follow-up call
            history.add(new GeminiChatClient.Turn("model", explanation));
            history.add(new GeminiChatClient.Turn("user", "Tool result: " + toolResultText(toolResp)));

            // Second call for final text response
            geminiResp = gemini.generate(apiKey, systemInstruction, history, FUNCTION_DECLARATIONS);
            parts = extractParts(geminiResp);
        }

        String reply = parts.stream()
            .filter(p -> p.containsKey("text"))
            .map(p -> (String) p.get("text"))
            .collect(Collectors.joining(" "))
            .trim();

        if (reply.isEmpty()) reply = "I couldn't generate a response. Please try again.";

        return new ChatResponse(reply, toolCalls, toolResults);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> extractParts(Map<String, Object> resp) {
        try {
            var candidates = (List<Map<String, Object>>) resp.get("candidates");
            if (candidates == null || candidates.isEmpty()) return List.of();
            var content = (Map<String, Object>) candidates.get(0).get("content");
            if (content == null) return List.of();
            var partsList = (List<Map<String, Object>>) content.get("parts");
            return partsList != null ? partsList : List.of();
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String toolResultText(ToolResponse r) {
        if ("ok".equals(r.status()) && r.result() instanceof Map<?,?> m) {
            return "rows=" + m.get("count") + " data=" + m.get("rows");
        }
        return r.status() + (r.error() != null ? ": " + r.error() : "");
    }
}
