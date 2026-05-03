package com.pulsar.content.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pulsar.content.service.ContentItemService;
import com.pulsar.kernel.tenant.TenantContext;
import com.pulsar.kernel.tenant.TenantDataSources;
import com.pulsar.kernel.tenant.TenantInfo;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Tests for the one-shot reindex admin endpoint. Direct-instantiation;
 * the per-row "format + push" delegates to ContentItemService.pushToWorkspace
 * which is covered separately. The contract this controller owns is:
 * (1) only scan rows with NULL anythingllm_doc, (2) write back the
 * returned doc location, (3) tally pushed/skipped/scanned correctly.
 */
class ContentReindexControllerTest {

    private TenantDataSources tenantDs;
    private ContentItemService svc;
    private ContentReindexController controller;

    @BeforeEach
    void setUp() {
        tenantDs = Mockito.mock(TenantDataSources.class);
        svc = Mockito.mock(ContentItemService.class);
        controller = new ContentReindexController(tenantDs, svc);
        when(tenantDs.forDb(anyString())).thenReturn(Mockito.mock(DataSource.class));
        TenantContext.set(new TenantInfo(
            1L, "acme", "Acme", "acme_db", Set.of("content"), false));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void reindex_pushesEachUnindexedRow_andStoresReturnedDocLocation() {
        Map<String, Object> contactRow = Map.of(
            "item_id", "abhi", "title", "Abhi", "type", "contact",
            "content_data", "{\"phone\":\"123\",\"runbook\":\"call him\"}"
        );
        Map<String, Object> guideRow = Map.of(
            "item_id", "printer", "title", "Printer Fix", "type", "runbook",
            "content_data", "{\"content\":\"do the thing\"}"
        );
        when(svc.pushToWorkspace(eq("acme"), eq("contact"), eq("Abhi"), any()))
            .thenReturn("custom-documents/raw-abhi.json");
        when(svc.pushToWorkspace(eq("acme"), eq("runbook"), eq("Printer Fix"), any()))
            .thenReturn("custom-documents/raw-printer.json");

        try (MockedConstruction<JdbcTemplate> mock = Mockito.mockConstruction(JdbcTemplate.class,
                (jdbc, ctx) -> {
                    when(jdbc.queryForList(anyString())).thenReturn(List.of(contactRow, guideRow));
                    when(jdbc.update(anyString(), anyString(), anyString())).thenReturn(1);
                })) {

            Map<String, Object> resp = controller.reindex();

            assertEquals(2, resp.get("scanned"));
            assertEquals(2, resp.get("pushed"));
            assertEquals(0, resp.get("skipped"));

            JdbcTemplate jdbc = mock.constructed().get(0);
            verify(jdbc, times(2)).update(anyString(), anyString(), anyString());
        }
    }

    @Test
    void reindex_skipsRowsWherePushReturnsNull_butKeepsScanning() {
        // pushToWorkspace returns null for rows whose body has no
        // indexable text, or when the LLM is offline. The endpoint
        // must NOT abort early — it should tally-and-continue.
        Map<String, Object> emptyRow = Map.of(
            "item_id", "blank", "title", "Empty", "type", "contact",
            "content_data", "{}"
        );
        Map<String, Object> realRow = Map.of(
            "item_id", "real", "title", "Real", "type", "runbook",
            "content_data", "{\"content\":\"actual body\"}"
        );
        when(svc.pushToWorkspace(eq("acme"), anyString(), eq("Empty"), any())).thenReturn(null);
        when(svc.pushToWorkspace(eq("acme"), anyString(), eq("Real"), any()))
            .thenReturn("custom-documents/raw-real.json");

        try (MockedConstruction<JdbcTemplate> mock = Mockito.mockConstruction(JdbcTemplate.class,
                (jdbc, ctx) -> {
                    when(jdbc.queryForList(anyString())).thenReturn(List.of(emptyRow, realRow));
                    when(jdbc.update(anyString(), anyString(), anyString())).thenReturn(1);
                })) {

            Map<String, Object> resp = controller.reindex();

            assertEquals(2, resp.get("scanned"));
            assertEquals(1, resp.get("pushed"));
            assertEquals(1, resp.get("skipped"));

            JdbcTemplate jdbc = mock.constructed().get(0);
            // Only the row that pushed successfully gets a UPDATE; the
            // skipped row stays NULL so a future reindex can retry it.
            verify(jdbc, times(1)).update(anyString(), eq("custom-documents/raw-real.json"), eq("real"));
        }
    }

    @Test
    void reindex_invalidJson_isCountedAsSkipped_notFatal() {
        Map<String, Object> badRow = Map.of(
            "item_id", "bad", "title", "Bad", "type", "runbook",
            "content_data", "not-json{"
        );
        try (MockedConstruction<JdbcTemplate> mock = Mockito.mockConstruction(JdbcTemplate.class,
                (jdbc, ctx) -> when(jdbc.queryForList(anyString())).thenReturn(List.of(badRow)))) {

            Map<String, Object> resp = controller.reindex();

            assertEquals(1, resp.get("scanned"));
            assertEquals(0, resp.get("pushed"));
            assertEquals(1, resp.get("skipped"));
            // pushToWorkspace must NOT be called for unparseable rows.
            verify(svc, never()).pushToWorkspace(anyString(), anyString(), anyString(), any());
        }
    }
}
