package com.pulsar.callermatch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulsar.kernel.tenant.TenantDataSources;
import com.pulsar.kernel.voice.CallRingingEvent;
import com.pulsar.opendentalai.opendental.OpendentalQueryClient;
import com.pulsar.opendentalai.opendental.OpendentalQueryClient.QueryRequest;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Manages SSE subscriptions for live screen-pop events and handles
 * {@link CallRingingEvent}s published by the kernel webhook controller.
 *
 * <p>When a RINGING event arrives the patient is looked up in the tenant's
 * OpenDental database and the resulting card is pushed to every open browser
 * tab that has subscribed via {@link #subscribe(String)}.
 */
@Component
public class CallerMatchEventService {

    private final Map<String, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final TenantDataSources tenantDs;
    private final OpendentalQueryClient od;
    private final ObjectMapper mapper = new ObjectMapper();

    public CallerMatchEventService(TenantDataSources tenantDs, OpendentalQueryClient od) {
        this.tenantDs = tenantDs;
        this.od = od;
    }

    /**
     * Subscribe the caller to screen-pop events for a specific tenant DB.
     * The returned emitter stays open for up to 30 minutes; the browser's
     * EventSource API will reconnect automatically when it times out.
     */
    public SseEmitter subscribe(String dbName) {
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L); // 30 min
        emitters.computeIfAbsent(dbName, k -> new CopyOnWriteArrayList<>()).add(emitter);
        Runnable cleanup = () -> {
            var list = emitters.get(dbName);
            if (list != null) list.remove(emitter);
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());
        return emitter;
    }

    @EventListener
    public void onRinging(CallRingingEvent event) {
        var list = emitters.get(event.dbName());
        if (list == null || list.isEmpty()) return;

        Map<String, Object> card = buildCard(event.callerPhone(), event.dbName());
        card.put("type", "screen-pop");

        String json;
        try {
            json = mapper.writeValueAsString(card);
        } catch (Exception e) {
            return;
        }

        List<SseEmitter> dead = new ArrayList<>();
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name("screen-pop").data(json).build());
            } catch (Exception e) {
                dead.add(emitter);
            }
        }
        list.removeAll(dead);
    }

    // ───────────────────────────────────────────────────────────────────────
    // Patient lookup — same logic as CallerMatchController, adapted to use
    // dbName directly instead of TenantContext (we're outside a JWT request).

    public Map<String, Object> buildCard(String phone, String dbName) {
        Map<String, Object> card = new HashMap<>();
        card.put("phone", phone);
        card.put("matched", false);

        var creds = readCredentials(dbName);
        if (creds.isEmpty()) {
            card.put("error", "opendental_ai_not_onboarded");
            return card;
        }

        String normalized = normalizePhone(phone);
        String sql = """
            SELECT p.PatNum, p.FName, p.LName, p.Birthdate, p.BalTotal, p.Email, p.HmPhone, p.WirelessPhone, p.WkPhone
            FROM patient p
            WHERE REPLACE(REPLACE(REPLACE(REPLACE(p.WirelessPhone, '-', ''), ' ', ''), '(', ''), ')', '') LIKE '%%%s%%'
               OR REPLACE(REPLACE(REPLACE(REPLACE(p.HmPhone,       '-', ''), ' ', ''), '(', ''), ')', '') LIKE '%%%s%%'
               OR REPLACE(REPLACE(REPLACE(REPLACE(p.WkPhone,       '-', ''), ' ', ''), '(', ''), ')', '') LIKE '%%%s%%'
            LIMIT 1;
            """.formatted(normalized, normalized, normalized);

        try {
            var result = od.run(new QueryRequest(creds.get().odDev(), creds.get().odCust(), sql));
            if (result.rows().isEmpty()) return card;
            Map<String, Object> p = result.rows().get(0);
            card.put("matched", true);
            card.put("patNum", p.get("PatNum"));
            card.put("firstName", p.get("FName"));
            card.put("lastName", p.get("LName"));
            card.put("birthdate", p.get("Birthdate"));
            card.put("balance", p.get("BalTotal"));
            card.put("email", p.get("Email"));

            Number pn = (Number) p.get("PatNum");
            if (pn != null) {
                card.put("nextAppt", fetchNextAppt(creds.get(), pn.longValue()));
                card.put("lastAppt", fetchLastAppt(creds.get(), pn.longValue()));
                card.put("overdueRecalls", fetchOverdueRecalls(creds.get(), pn.longValue()));
            }
        } catch (IOException e) {
            card.put("error", "opendental_query_failed: " + e.getMessage());
        } catch (OpendentalQueryClient.OdQueryException e) {
            card.put("error", e.getMessage());
        }
        return card;
    }

    private Object fetchNextAppt(Creds c, long patNum) {
        return safeFirst(c,
            "SELECT AptDateTime, AptStatus FROM appointment WHERE PatNum = " + patNum +
            " AND AptDateTime >= NOW() AND AptStatus = 1 ORDER BY AptDateTime LIMIT 1;"
        );
    }

    private Object fetchLastAppt(Creds c, long patNum) {
        return safeFirst(c,
            "SELECT AptDateTime, AptStatus FROM appointment WHERE PatNum = " + patNum +
            " AND AptDateTime < NOW() AND AptStatus = 2 ORDER BY AptDateTime DESC LIMIT 1;"
        );
    }

    private Object fetchOverdueRecalls(Creds c, long patNum) {
        return safeFirst(c,
            "SELECT COUNT(*) AS overdue FROM recall WHERE PatNum = " + patNum +
            " AND IsDisabled = 0 AND DateDue < CURDATE() AND DateDue > '1900-01-01';"
        );
    }

    private Object safeFirst(Creds c, String sql) {
        try {
            var r = od.run(new QueryRequest(c.odDev(), c.odCust(), sql));
            return r.rows().isEmpty() ? null : r.rows().get(0);
        } catch (Exception ignored) { return null; }
    }

    private record Creds(String odDev, String odCust) {}

    private Optional<Creds> readCredentials(String dbName) {
        JdbcTemplate jdbc = new JdbcTemplate(tenantDs.forDb(dbName));
        var rows = jdbc.queryForList(
            "SELECT od_developer_key, od_customer_key FROM opendental_ai_config WHERE id = 1"
        );
        if (rows.isEmpty()) return Optional.empty();
        return Optional.of(new Creds(
            (String) rows.get(0).get("od_developer_key"),
            (String) rows.get(0).get("od_customer_key")
        ));
    }

    /** Strip everything except the last 10 digits so we match OD formats that vary. */
    private static String normalizePhone(String raw) {
        String digits = raw.replaceAll("[^0-9]", "");
        return digits.length() > 10 ? digits.substring(digits.length() - 10) : digits;
    }
}
