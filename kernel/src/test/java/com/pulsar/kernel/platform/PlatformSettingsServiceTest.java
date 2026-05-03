package com.pulsar.kernel.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
 * Tests for {@link PlatformSettingsService}: encrypt/decrypt round-trip,
 * env-fallback behavior, prod-mode master-key requirement, unknown-key
 * rejection.
 */
class PlatformSettingsServiceTest {

    private static final String DB_NAME = "test_ps_" + UUID.randomUUID().toString().replace("-", "");
    private static final String JDBC = "jdbc:h2:mem:" + DB_NAME + ";MODE=MYSQL;DB_CLOSE_DELAY=-1";
    private static final String MASTER_KEY_B64 = Base64.getEncoder().encodeToString(new byte[32]);

    private DataSource ds;

    @BeforeAll
    static void initSchema() throws Exception {
        try (Connection c = DriverManager.getConnection(JDBC); var s = c.createStatement()) {
            s.executeUpdate(
                "CREATE TABLE platform_settings (\n"
              + "    id BIGINT AUTO_INCREMENT PRIMARY KEY,\n"
              + "    setting_key VARCHAR(128) NOT NULL,\n"
              + "    value_ciphertext VARBINARY(2048),\n"
              + "    value_iv VARBINARY(12),\n"
              + "    value_tag VARBINARY(16),\n"
              + "    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,\n"
              + "    updated_by_email VARCHAR(255),\n"
              + "    UNIQUE (setting_key)\n"
              + ")");
        }
    }

    @BeforeEach
    void cleanRows() throws Exception {
        try (Connection c = DriverManager.getConnection(JDBC); var s = c.createStatement()) {
            s.executeUpdate("DELETE FROM platform_settings");
        }
        ds = new SingleConnDataSource(JDBC);
    }

    @Test
    void encryptedRoundTrip() {
        PlatformSettingsService svc = new PlatformSettingsService(ds, MASTER_KEY_B64, new MockEnvironment());
        svc.set("anythingllm.api_key", "real-key-value", "admin@x");
        assertThat(svc.resolve("anythingllm.api_key")).isEqualTo("real-key-value");
    }

    @Test
    void resolveOrFallbackUsesEnvWhenDbEmpty() {
        PlatformSettingsService svc = new PlatformSettingsService(ds, MASTER_KEY_B64, new MockEnvironment());
        assertThat(svc.resolveOrFallback("anythingllm.url", "http://from-env:3001"))
            .isEqualTo("http://from-env:3001");
    }

    @Test
    void resolveOrFallbackPrefersDbOverEnv() {
        PlatformSettingsService svc = new PlatformSettingsService(ds, MASTER_KEY_B64, new MockEnvironment());
        svc.set("anythingllm.url", "http://from-db:9000", "admin@x");
        assertThat(svc.resolveOrFallback("anythingllm.url", "http://from-env:3001"))
            .isEqualTo("http://from-db:9000");
    }

    @Test
    void clearLeavesRowForAuditButReturnsNull() {
        PlatformSettingsService svc = new PlatformSettingsService(ds, MASTER_KEY_B64, new MockEnvironment());
        svc.set("anythingllm.api_key", "v", "a@x");
        svc.clear("anythingllm.api_key", "a@x");
        assertThat(svc.resolve("anythingllm.api_key")).isNull();
        assertThat(svc.status("anythingllm.api_key", "").hasValue()).isFalse();
    }

    @Test
    void rejectsUnknownSettingKey() {
        PlatformSettingsService svc = new PlatformSettingsService(ds, MASTER_KEY_B64, new MockEnvironment());
        assertThatThrownBy(() -> svc.set("unknown.thing", "v", null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unknown");
    }

    @Test
    void refusesToStartInProdWithoutMasterKey() {
        MockEnvironment prodEnv = new MockEnvironment();
        prodEnv.setActiveProfiles("prod");
        assertThatThrownBy(() -> new PlatformSettingsService(ds, "", prodEnv))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("PULSAR_CREDENTIALS_MASTER_KEY");
    }

    @Test
    void devModeAllowsPlaintext() {
        PlatformSettingsService svc = new PlatformSettingsService(ds, "", new MockEnvironment());
        assertThat(svc.encryptionEnabled()).isFalse();
        svc.set("anythingllm.url", "http://x", null);
        assertThat(svc.resolve("anythingllm.url")).isEqualTo("http://x");
    }

    @Test
    void statusEnvFallbackFlag() {
        PlatformSettingsService svc = new PlatformSettingsService(ds, MASTER_KEY_B64, new MockEnvironment());
        var noEnv = svc.status("anythingllm.api_key", "");
        assertThat(noEnv.hasValue()).isFalse();
        assertThat(noEnv.envFallbackAvailable()).isFalse();

        var withEnv = svc.status("anythingllm.api_key", "envValue");
        assertThat(withEnv.envFallbackAvailable()).isTrue();
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
