package com.pulsar.opendentalai.safety;

import org.springframework.stereotype.Component;

/**
 * Guardrail for SQL that Gemini authors. Enforces read-only v1 policy:
 *   1. Exactly one statement (no ';' that isn't trailing)
 *   2. Starts with SELECT (or WITH … SELECT)
 *   3. Contains no DDL/DML keywords
 *   4. Has a LIMIT clause — we auto-append one if missing, up to MAX_ROWS
 *
 * The goal isn't a perfect SQL parser; it's defence in depth. The OpenDental
 * Query API also refuses writes, so this is the first of two lines of defence.
 */
@Component
public class SqlSafetyCheck {

    /** Hard cap on rows returned to Gemini. Picked to fit comfortably in a tool-response payload. */
    public static final int MAX_ROWS = 1000;

    private static final String[] FORBIDDEN = {
        " insert ", " update ", " delete ", " drop ", " truncate ", " alter ",
        " create ", " grant ", " revoke ", " replace ", " rename ", " call ",
        " handler ", " load ", " lock ", " unlock ", " set ", " use ",
        "--", "/*"  // comment injection
    };

    public record Result(boolean ok, String reason, String sanitizedSql) {
        public static Result reject(String why) { return new Result(false, why, null); }
        public static Result accept(String sql) { return new Result(true, null, sql); }
    }

    public Result check(String sql) {
        if (sql == null || sql.isBlank()) return Result.reject("empty sql");
        String s = sql.trim();
        if (s.endsWith(";")) s = s.substring(0, s.length() - 1).trim();

        // Multiple statements disallowed.
        if (s.contains(";")) return Result.reject("only one statement allowed");

        String padded = " " + s.toLowerCase() + " ";
        for (String forbidden : FORBIDDEN) {
            if (padded.contains(forbidden)) {
                return Result.reject("forbidden keyword: " + forbidden.trim());
            }
        }

        String lower = s.toLowerCase();
        boolean startsOk = lower.startsWith("select ") || lower.startsWith("with ");
        if (!startsOk) return Result.reject("query must start with SELECT or WITH");

        // Auto-limit. If the query has LIMIT we trust it (OD Query API also caps).
        // Otherwise append LIMIT MAX_ROWS so a runaway SELECT can't spoil the tool response.
        if (!lower.contains(" limit ")) {
            s = s + " LIMIT " + MAX_ROWS;
        }
        return Result.accept(s);
    }
}
