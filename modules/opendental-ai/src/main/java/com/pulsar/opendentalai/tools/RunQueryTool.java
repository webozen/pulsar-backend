package com.pulsar.opendentalai.tools;

import com.pulsar.opendentalai.audit.OpendentalQueryAuditLog;
import com.pulsar.opendentalai.opendental.OpendentalQueryClient;
import com.pulsar.opendentalai.opendental.OpendentalQueryClient.QueryRequest;
import com.pulsar.opendentalai.opendental.OpendentalQueryClient.QueryResult;
import com.pulsar.opendentalai.safety.SqlSafetyCheck;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Backend implementation of the {@code run_opendental_query} function Gemini
 * calls. Orchestrates safety-check → OD Query API call → audit log and returns
 * a structured tool response suitable for passing back into the Live API.
 */
@Component
public class RunQueryTool {
    private static final Logger log = LoggerFactory.getLogger(RunQueryTool.class);

    private final SqlSafetyCheck safety;
    private final OpendentalQueryClient client;

    public RunQueryTool(SqlSafetyCheck safety, OpendentalQueryClient client) {
        this.safety = safety;
        this.client = client;
    }

    public record Invocation(
        String sessionId,
        String userEmail,
        String question,
        String sql,
        String developerKey,
        String customerKey,
        DataSource tenantDs
    ) {}

    /** Shape returned to Gemini as the function's "response" map. */
    public record ToolResponse(String status, Object result, String error) {
        public static ToolResponse ok(List<Map<String, Object>> rows, int count) {
            return new ToolResponse("ok", Map.of("rows", rows, "count", count), null);
        }
        public static ToolResponse rejected(String why) {
            return new ToolResponse("sql_rejected", null, why);
        }
        public static ToolResponse odError(String detail) {
            return new ToolResponse("od_error", null, detail);
        }
    }

    public ToolResponse run(Invocation inv) {
        long t0 = System.currentTimeMillis();
        SqlSafetyCheck.Result check = safety.check(inv.sql());
        if (!check.ok()) {
            audit(inv, inv.sql(), null, elapsed(t0), "sql_rejected", check.reason());
            return ToolResponse.rejected(check.reason());
        }

        try {
            QueryResult res = client.run(new QueryRequest(inv.developerKey(), inv.customerKey(), check.sanitizedSql()));
            audit(inv, check.sanitizedSql(), res.rowCount(), elapsed(t0), "ok", null);
            return ToolResponse.ok(res.rows(), res.rowCount());
        } catch (OpendentalQueryClient.OdQueryException e) {
            log.warn("OpenDental query failed for session={}: {}", inv.sessionId(), e.getMessage());
            audit(inv, check.sanitizedSql(), null, elapsed(t0), "od_error", e.getMessage());
            return ToolResponse.odError(e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error running tool: {}", e.getMessage(), e);
            audit(inv, check.sanitizedSql(), null, elapsed(t0), "internal_error", e.getMessage());
            return ToolResponse.odError("internal error");
        }
    }

    private static int elapsed(long t0) { return (int) (System.currentTimeMillis() - t0); }

    private static void audit(Invocation inv, String sql, Integer rowCount,
                              int elapsedMs, String status, String error) {
        try {
            OpendentalQueryAuditLog.insert(inv.tenantDs(), new OpendentalQueryAuditLog.Record(
                inv.sessionId(), inv.userEmail(), inv.question(), sql,
                rowCount, elapsedMs, status, error
            ));
        } catch (Exception e) {
            // Never fail the user's query because the audit log write failed.
            log.warn("Audit log write failed (session={}): {}", inv.sessionId(), e.getMessage());
        }
    }
}
