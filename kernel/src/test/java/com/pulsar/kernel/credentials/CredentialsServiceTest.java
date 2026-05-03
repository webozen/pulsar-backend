package com.pulsar.kernel.credentials;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

/**
 * Unit tests for {@link CredentialsService}. Uses an in-memory H2 database in
 * MySQL-compatibility mode to exercise the SQL paths without standing up a
 * real MySQL container.
 */
class CredentialsServiceTest {

    private static final String DB_NAME = "test_creds_" + UUID.randomUUID().toString().replace("-", "");
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

    private static Environment devEnv() {
        // No active profiles → dev-like (preserves local DX).
        return new MockEnvironment();
    }

    private static Environment prodEnv() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        return env;
    }

    @Test
    void encryptedRoundTrip() {
        CredentialsService svc = new CredentialsService(stubDs, MASTER_KEY_B64, "", devEnv());
        assertThat(svc.encryptionEnabled()).isTrue();

        svc.set("any-db", "gemini", "api_key", "AIzaSy-secret-value", "admin@x.com", "super_admin");

        CredentialsService.Resolution r = svc.resolve("any-db", "gemini", "api_key");
        assertThat(r.value()).isEqualTo("AIzaSy-secret-value");
        assertThat(r.source()).isEqualTo(CredentialsService.Source.TENANT);
    }

    @Test
    void plaintextFallbackWhenMasterKeyAbsentInDev() {
        CredentialsService svc = new CredentialsService(stubDs, "", "", devEnv());
        assertThat(svc.encryptionEnabled()).isFalse();

        svc.set("any-db", "gemini", "api_key", "plaintext-key", null, "super_admin");
        CredentialsService.Resolution r = svc.resolve("any-db", "gemini", "api_key");
        assertThat(r.value()).isEqualTo("plaintext-key");
        assertThat(r.source()).isEqualTo(CredentialsService.Source.TENANT);
    }

    @Test
    void refusesToStartInProdWithoutMasterKey() {
        assertThatThrownBy(() -> new CredentialsService(stubDs, "", "", prodEnv()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("PULSAR_CREDENTIALS_MASTER_KEY must be set in prod-like profiles");
    }

    @Test
    void clearEmptiesValueButKeepsRowForAudit() {
        CredentialsService svc = new CredentialsService(stubDs, MASTER_KEY_B64, "", devEnv());
        svc.set("any-db", "gemini", "api_key", "abc", "a@x", "super_admin");
        assertThat(svc.status("any-db", "gemini", "api_key").hasTenantValue()).isTrue();

        svc.clear("any-db", "gemini", "api_key", "a@x", "super_admin");
        assertThat(svc.status("any-db", "gemini", "api_key").hasTenantValue()).isFalse();

        Integer rows = new JdbcTemplate(stubDs.forDb("any-db")).queryForObject(
            "SELECT COUNT(*) FROM tenant_credentials WHERE provider='gemini' AND key_name='api_key'",
            Integer.class);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void setUseDefaultDoesNotTouchCiphertext() {
        CredentialsService svc = new CredentialsService(stubDs, MASTER_KEY_B64, "the-platform-default", devEnv());
        svc.set("any-db", "gemini", "api_key", "tenant-key", null, "super_admin");
        svc.setUseDefault("any-db", "gemini", "api_key", true, null, "super_admin");

        // Tenant ciphertext is still there — flipping back proves it.
        svc.setUseDefault("any-db", "gemini", "api_key", false, null, "super_admin");
        assertThat(svc.resolve("any-db", "gemini", "api_key").value()).isEqualTo("tenant-key");
    }

    @Test
    void resolutionPriorityPlatformDefaultWinsWhenFlagSet() {
        CredentialsService svc = new CredentialsService(stubDs, MASTER_KEY_B64, "default-from-env", devEnv());
        svc.set("any-db", "gemini", "api_key", "tenant-key", null, "super_admin");
        svc.setUseDefault("any-db", "gemini", "api_key", true, null, "super_admin");

        CredentialsService.Resolution r = svc.resolve("any-db", "gemini", "api_key");
        assertThat(r.value()).isEqualTo("default-from-env");
        assertThat(r.source()).isEqualTo(CredentialsService.Source.PLATFORM_DEFAULT);
    }

    @Test
    void resolveReturnsNoneWhenNothingConfigured() {
        CredentialsService svc = new CredentialsService(stubDs, MASTER_KEY_B64, "", devEnv());
        CredentialsService.Resolution r = svc.resolve("any-db", "gemini", "api_key");
        assertThat(r.value()).isNull();
        assertThat(r.source()).isEqualTo(CredentialsService.Source.NONE);
    }

    @Test
    void useDefaultWithoutEnvDefaultReturnsNone() {
        CredentialsService svc = new CredentialsService(stubDs, MASTER_KEY_B64, "", devEnv());
        svc.set("any-db", "gemini", "api_key", "tenant-key", null, "super_admin");
        svc.setUseDefault("any-db", "gemini", "api_key", true, null, "super_admin");

        // No PULSAR_DEFAULT_GEMINI_KEY in env, useDefault=true → NONE (admin must
        // either set the env var or untick the box).
        CredentialsService.Resolution r = svc.resolve("any-db", "gemini", "api_key");
        assertThat(r.value()).isNull();
        assertThat(r.source()).isEqualTo(CredentialsService.Source.NONE);
    }

    @Test
    void auditColumnsArePopulated() {
        CredentialsService svc = new CredentialsService(stubDs, MASTER_KEY_B64, "", devEnv());
        svc.set("any-db", "gemini", "api_key", "v", "alice@example.com", "tenant_user");

        var row = new JdbcTemplate(stubDs.forDb("any-db")).queryForMap(
            "SELECT updated_by_email, updated_by_role FROM tenant_credentials "
            + "WHERE provider='gemini' AND key_name='api_key'");
        assertThat(row.get("UPDATED_BY_EMAIL")).isEqualTo("alice@example.com");
        assertThat(row.get("UPDATED_BY_ROLE")).isEqualTo("tenant_user");
    }

    @Test
    void platformDefaultAvailableReflectsEnv() {
        CredentialsService withDefault = new CredentialsService(stubDs, MASTER_KEY_B64, "the-default", devEnv());
        assertThat(withDefault.platformDefaultAvailable("gemini")).isTrue();

        CredentialsService noDefault = new CredentialsService(stubDs, MASTER_KEY_B64, "", devEnv());
        assertThat(noDefault.platformDefaultAvailable("gemini")).isFalse();
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
