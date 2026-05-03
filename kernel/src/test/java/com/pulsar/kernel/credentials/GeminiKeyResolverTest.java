package com.pulsar.kernel.credentials;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pulsar.kernel.tenant.TenantDataSources;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Base64;
import java.util.UUID;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Tests for the {@link GeminiKeyResolver} facade. Exercises the API surface the
 * 6 modules consuming Gemini today depend on (translate WS handler + 5 chat
 * controllers).
 */
class GeminiKeyResolverTest {

    private static final String DB_NAME = "test_resolver_" + UUID.randomUUID().toString().replace("-", "");
    private static final String JDBC = "jdbc:h2:mem:" + DB_NAME + ";MODE=MYSQL;DB_CLOSE_DELAY=-1";
    private static final String MASTER_KEY_B64 = Base64.getEncoder().encodeToString(new byte[32]);

    private TenantDataSources stubDs;

    @BeforeAll
    static void initSchema() throws Exception {
        try (Connection c = DriverManager.getConnection(JDBC); var s = c.createStatement()) {
            s.executeUpdate(
                "CREATE TABLE tenant_credentials (\n"
              + "    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,\n"
              + "    provider             VARCHAR(64)  NOT NULL,\n"
              + "    key_name             VARCHAR(64)  NOT NULL,\n"
              + "    value_ciphertext     VARBINARY(2048),\n"
              + "    value_iv             VARBINARY(12),\n"
              + "    value_tag            VARBINARY(16),\n"
              + "    use_platform_default BOOLEAN      NOT NULL DEFAULT FALSE,\n"
              + "    updated_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,\n"
              + "    updated_by_email     VARCHAR(255),\n"
              + "    updated_by_role      VARCHAR(32),\n"
              + "    UNIQUE (provider, key_name)\n"
              + ")");
        }
    }

    @BeforeEach
    void cleanRows() throws Exception {
        try (Connection c = DriverManager.getConnection(JDBC); var s = c.createStatement()) {
            s.executeUpdate("DELETE FROM tenant_credentials");
        }
        stubDs = mock(TenantDataSources.class);
        when(stubDs.forDb(anyString())).thenReturn(new SingleConnDataSource(JDBC));
    }

    private GeminiKeyResolver resolverWith(String defaultKey) {
        CredentialsService creds = new CredentialsService(stubDs, MASTER_KEY_B64, defaultKey, new MockEnvironment());
        return new GeminiKeyResolver(creds);
    }

    @Test
    void resolveAfterUpdateReturnsTenantSource() {
        GeminiKeyResolver r = resolverWith("");
        r.updateForDb("any-db", "AIza-tenant", null);

        GeminiKeyResolver.Resolution res = r.resolveForDb("any-db");
        assertThat(res.apiKey()).isEqualTo("AIza-tenant");
        assertThat(res.source()).isEqualTo(GeminiKeyResolver.Source.TENANT);
    }

    @Test
    void clearViaUpdateEmptyString() {
        GeminiKeyResolver r = resolverWith("");
        r.updateForDb("any-db", "AIza-tenant", null);
        r.updateForDb("any-db", "", null);

        GeminiKeyResolver.TenantKeyStatus status = r.statusForDb("any-db");
        assertThat(status.hasTenantKey()).isFalse();
    }

    @Test
    void useDefaultRoutesToPlatformDefault() {
        GeminiKeyResolver r = resolverWith("PLATFORM-DEFAULT");
        r.updateForDb("any-db", "AIza-tenant", true);

        GeminiKeyResolver.Resolution res = r.resolveForDb("any-db");
        assertThat(res.apiKey()).isEqualTo("PLATFORM-DEFAULT");
        assertThat(res.source()).isEqualTo(GeminiKeyResolver.Source.PLATFORM_DEFAULT);
        assertThat(r.platformDefaultAvailable()).isTrue();
    }

    @Test
    void noKeyAnywhereReturnsNone() {
        GeminiKeyResolver r = resolverWith("");
        GeminiKeyResolver.Resolution res = r.resolveForDb("any-db");
        assertThat(res.apiKey()).isNull();
        assertThat(res.source()).isEqualTo(GeminiKeyResolver.Source.NONE);
        assertThat(r.statusForDb("any-db").hasTenantKey()).isFalse();
    }

    @Test
    void auditOverloadPropagatesEmailAndRole() {
        GeminiKeyResolver r = resolverWith("");
        r.updateForDb("any-db", "AIza-from-tenant-user", null, "alice@x.com", "tenant_user");

        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(stubDs.forDb("any-db"));
        var row = jdbc.queryForMap(
            "SELECT updated_by_email, updated_by_role FROM tenant_credentials WHERE provider='gemini' AND key_name='api_key'");
        assertThat(row.get("UPDATED_BY_EMAIL")).isEqualTo("alice@x.com");
        assertThat(row.get("UPDATED_BY_ROLE")).isEqualTo("tenant_user");
    }

    private static class SingleConnDataSource implements DataSource {
        private final String jdbc;
        SingleConnDataSource(String jdbc) { this.jdbc = jdbc; }
        @Override public Connection getConnection() throws SQLException { return DriverManager.getConnection(jdbc); }
        @Override public Connection getConnection(String u, String p) throws SQLException { return DriverManager.getConnection(jdbc, u, p); }
        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter out) {}
        @Override public void setLoginTimeout(int seconds) {}
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { throw new SQLFeatureNotSupportedException(); }
        @Override public <T> T unwrap(Class<T> iface) { return null; }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }

    @AfterAll
    static void closeDb() {
        try (Connection c = DriverManager.getConnection(JDBC); var s = c.createStatement()) {
            s.executeUpdate("SHUTDOWN");
        } catch (Exception ignored) {}
    }
}
