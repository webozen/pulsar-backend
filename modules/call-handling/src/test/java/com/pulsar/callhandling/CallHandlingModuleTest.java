package com.pulsar.callhandling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;

class CallHandlingModuleTest {

    private final CallHandlingModule module = new CallHandlingModule();

    @Test
    void id_is_call_handling() {
        assertThat(module.id()).isEqualTo("call-handling");
    }

    @Test
    void migration_locations_is_empty_for_manifest_only_meta_module() {
        // call-handling is a manifest-only meta-module: the migrations
        // for caller-match / call-intel / copilot are owned by those
        // sub-modules' own ModuleDefinition implementations. Re-declaring
        // them here would cause cross-module DDL collisions on tenants
        // that have both call-handling and any sub-suite active.
        assertThat(module.migrationLocations()).isEmpty();
    }

    @Test
    void is_onboarded_returns_true_when_opendental_ai_row_exists() {
        try (MockedConstruction<JdbcTemplate> m = Mockito.mockConstruction(JdbcTemplate.class,
            (mockJdbc, ctx) -> when(mockJdbc.queryForList(anyString()))
                .thenReturn(List.of(Map.of("1", 1))))) {
            assertThat(module.isOnboarded(mock(DataSource.class))).isTrue();
        }
    }

    @Test
    void is_onboarded_returns_false_when_no_row() {
        try (MockedConstruction<JdbcTemplate> m = Mockito.mockConstruction(JdbcTemplate.class,
            (mockJdbc, ctx) -> when(mockJdbc.queryForList(anyString()))
                .thenReturn(List.of()))) {
            assertThat(module.isOnboarded(mock(DataSource.class))).isFalse();
        }
    }
}
