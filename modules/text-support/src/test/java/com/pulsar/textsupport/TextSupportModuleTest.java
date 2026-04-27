package com.pulsar.textsupport;

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

class TextSupportModuleTest {

    private final TextSupportModule module = new TextSupportModule();

    @Test
    void id_is_text_support() {
        assertThat(module.id()).isEqualTo("text-support");
    }

    @Test
    void migration_locations_include_text_intel_only() {
        assertThat(module.migrationLocations())
            .containsExactly("classpath:db/migration/text-intel");
    }

    @Test
    void is_onboarded_returns_true_when_rc_oauth_configured() {
        try (MockedConstruction<JdbcTemplate> m = Mockito.mockConstruction(JdbcTemplate.class,
            (mockJdbc, ctx) -> when(mockJdbc.queryForList(anyString()))
                .thenReturn(List.of(Map.of("1", 1))))) {
            assertThat(module.isOnboarded(mock(DataSource.class))).isTrue();
        }
    }

    @Test
    void is_onboarded_returns_false_when_no_rc_oauth() {
        try (MockedConstruction<JdbcTemplate> m = Mockito.mockConstruction(JdbcTemplate.class,
            (mockJdbc, ctx) -> when(mockJdbc.queryForList(anyString()))
                .thenReturn(List.of()))) {
            assertThat(module.isOnboarded(mock(DataSource.class))).isFalse();
        }
    }
}
