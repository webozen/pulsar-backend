package com.pulsar.textintel.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import org.mockito.ArgumentMatchers;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pulsar.kernel.tenant.TenantContext;
import com.pulsar.kernel.tenant.TenantDataSources;
import com.pulsar.kernel.tenant.TenantInfo;
import com.pulsar.kernel.text.TextEvent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;

class TextThreadStoreTest {

    private TenantDataSources tenantDs;
    private TextThreadStore store;

    @BeforeEach
    void setUp() {
        tenantDs = mock(TenantDataSources.class);
        when(tenantDs.forDb(anyString())).thenReturn(mock(DataSource.class));
        store = new TextThreadStore(tenantDs);
        TenantContext.set(new TenantInfo(1L, "acme", "Acme", "acme_db", Set.of(), false));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ---- persist — new thread, new message --------------------------------

    @Test
    void persist_new_thread_and_new_message_returns_thread_id() {
        TextEvent ev = new TextEvent(
            TextEvent.EventType.RECEIVED, "rc-001", "msg-001", "thread-key-1",
            TextEvent.Direction.INBOUND, "+15550001111", "+15559998888",
            "Hello", List.of(), Instant.now()
        );

        try (MockedConstruction<JdbcTemplate> m = Mockito.mockConstruction(JdbcTemplate.class,
            (mockJdbc, ctx) -> {
                when(mockJdbc.queryForObject(anyString(), eq(Long.class), any(), any()))
                    .thenReturn(42L);
                when(mockJdbc.queryForList(anyString(), ArgumentMatchers.<Object[]>any()))
                    .thenReturn(List.of());
            })) {

            long threadId = store.persist(ev);

            assertThat(threadId).isEqualTo(42L);
            JdbcTemplate jdbc = m.constructed().get(0);
            // thread INSERT called
            verify(jdbc).update(anyString(), any(), any(), any(), any());
            // thread SELECT called
            verify(jdbc).queryForObject(anyString(), eq(Long.class), any(), any());
            // duplicate check called
            verify(jdbc).queryForList(anyString(), eq("msg-001"));
            // message INSERT called
            verify(jdbc).update(anyString(),
                eq(42L), eq("msg-001"), eq("inbound"),
                any(), any(), any(), any(), any(), any());
        }
    }

    // ---- persist — duplicate providerMessageId is a no-op -----------------

    @Test
    void persist_duplicate_provider_message_id_skips_message_insert() {
        TextEvent ev = new TextEvent(
            TextEvent.EventType.RECEIVED, "rc-001", "msg-dup", "thread-key-2",
            TextEvent.Direction.INBOUND, "+15550001111", "+15559998888",
            "Dup msg", List.of(), Instant.now()
        );

        try (MockedConstruction<JdbcTemplate> m = Mockito.mockConstruction(JdbcTemplate.class,
            (mockJdbc, ctx) -> {
                when(mockJdbc.queryForObject(anyString(), eq(Long.class), any(), any()))
                    .thenReturn(7L);
                // simulate existing row — duplicate check returns non-empty list
                when(mockJdbc.queryForList(anyString(), ArgumentMatchers.<Object[]>any()))
                    .thenReturn(List.of(Map.of("1", 1)));
            })) {

            long threadId = store.persist(ev);

            assertThat(threadId).isEqualTo(7L);
            JdbcTemplate jdbc = m.constructed().get(0);
            // duplicate check was performed
            verify(jdbc).queryForList(anyString(), eq("msg-dup"));
            // message INSERT must NOT be called
            verify(jdbc, never()).update(anyString(),
                eq(7L), any(), any(), any(), any(), any(), any(), any(), any());
        }
    }

    // ---- persist — null providerMessageId skips duplicate check -----------

    @Test
    void persist_null_provider_message_id_skips_duplicate_check_and_inserts_message() {
        TextEvent ev = new TextEvent(
            TextEvent.EventType.SENT, "rc-001", null, "thread-key-3",
            TextEvent.Direction.OUTBOUND, "+15559998888", "+15550001111",
            "No id msg", List.of(), Instant.now()
        );

        try (MockedConstruction<JdbcTemplate> m = Mockito.mockConstruction(JdbcTemplate.class,
            (mockJdbc, ctx) ->
                when(mockJdbc.queryForObject(anyString(), eq(Long.class), any(), any()))
                    .thenReturn(15L))) {

            long threadId = store.persist(ev);

            assertThat(threadId).isEqualTo(15L);
            JdbcTemplate jdbc = m.constructed().get(0);
            // duplicate check must NOT be called when providerMessageId is null
            verify(jdbc, never()).queryForList(anyString(), ArgumentMatchers.<Object[]>any());
            // message INSERT IS called (null providerMessageId is fine as a param)
            verify(jdbc).update(anyString(),
                eq(15L), eq(null), eq("outbound"),
                any(), any(), any(), any(), any(), any());
        }
    }

    // ---- persist — INBOUND uses fromPhone as patientPhone -----------------

    @Test
    void persist_inbound_uses_from_phone_as_patient_phone() {
        TextEvent ev = new TextEvent(
            TextEvent.EventType.RECEIVED, "rc-001", "msg-in", "thread-key-4",
            TextEvent.Direction.INBOUND, "+15550001111", "+15559998888",
            "Hi", List.of(), Instant.now()
        );

        try (MockedConstruction<JdbcTemplate> m = Mockito.mockConstruction(JdbcTemplate.class,
            (mockJdbc, ctx) -> {
                when(mockJdbc.queryForObject(anyString(), eq(Long.class), any(), any()))
                    .thenReturn(20L);
                when(mockJdbc.queryForList(anyString(), ArgumentMatchers.<Object[]>any()))
                    .thenReturn(List.of());
            })) {

            store.persist(ev);

            ArgumentCaptor<Object> argsCaptor = ArgumentCaptor.forClass(Object.class);
            // update(sql, providerId, threadKey, patientPhone, sentAt)
            verify(m.constructed().get(0)).update(anyString(),
                argsCaptor.capture(), argsCaptor.capture(),
                argsCaptor.capture(), argsCaptor.capture());
            List<Object> args = argsCaptor.getAllValues();
            // args[2] is patientPhone — for INBOUND it must be fromPhone
            assertThat(args.get(2)).isEqualTo("+15550001111");
        }
    }

    // ---- persist — OUTBOUND uses toPhone as patientPhone ------------------

    @Test
    void persist_outbound_uses_to_phone_as_patient_phone() {
        TextEvent ev = new TextEvent(
            TextEvent.EventType.SENT, "rc-001", "msg-out", "thread-key-5",
            TextEvent.Direction.OUTBOUND, "+15559998888", "+15550001111",
            "Reply", List.of(), Instant.now()
        );

        try (MockedConstruction<JdbcTemplate> m = Mockito.mockConstruction(JdbcTemplate.class,
            (mockJdbc, ctx) -> {
                when(mockJdbc.queryForObject(anyString(), eq(Long.class), any(), any()))
                    .thenReturn(21L);
                when(mockJdbc.queryForList(anyString(), ArgumentMatchers.<Object[]>any()))
                    .thenReturn(List.of());
            })) {

            store.persist(ev);

            ArgumentCaptor<Object> argsCaptor = ArgumentCaptor.forClass(Object.class);
            verify(m.constructed().get(0)).update(anyString(),
                argsCaptor.capture(), argsCaptor.capture(),
                argsCaptor.capture(), argsCaptor.capture());
            List<Object> args = argsCaptor.getAllValues();
            // args[2] is patientPhone — for OUTBOUND it must be toPhone
            assertThat(args.get(2)).isEqualTo("+15550001111");
        }
    }

    // ---- recentMessages — delegates to JdbcTemplate with correct args -----

    @Test
    void recent_messages_delegates_to_jdbc_with_correct_args_and_returns_list() {
        List<Map<String, Object>> expected = List.of(
            Map.of("id", 1L, "body", "first"),
            Map.of("id", 2L, "body", "second")
        );

        try (MockedConstruction<JdbcTemplate> m = Mockito.mockConstruction(JdbcTemplate.class,
            (mockJdbc, ctx) ->
                when(mockJdbc.queryForList(anyString(), eq(99L), eq(10)))
                    .thenReturn(expected))) {

            List<Map<String, Object>> result = store.recentMessages(99L, 10);

            assertThat(result).isEqualTo(expected);
            verify(m.constructed().get(0)).queryForList(anyString(), eq(99L), eq(10));
        }
    }

    // ---- persist — queryForObject returns null → returns 0 ----------------

    @Test
    void persist_query_for_object_returns_null_returns_zero() {
        TextEvent ev = new TextEvent(
            TextEvent.EventType.RECEIVED, "rc-001", "msg-x", "thread-key-6",
            TextEvent.Direction.INBOUND, "+15550001111", "+15559998888",
            "Ghost", List.of(), Instant.now()
        );

        try (MockedConstruction<JdbcTemplate> m = Mockito.mockConstruction(JdbcTemplate.class,
            (mockJdbc, ctx) ->
                when(mockJdbc.queryForObject(anyString(), eq(Long.class), any(), any()))
                    .thenReturn(null))) {

            long threadId = store.persist(ev);

            assertThat(threadId).isZero();
            // message INSERT must NOT be called when thread row is missing
            verify(m.constructed().get(0), never()).queryForList(anyString(), ArgumentMatchers.<Object[]>any());
        }
    }
}
