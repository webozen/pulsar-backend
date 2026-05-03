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
 * Tests for the Phase-2 resolvers: OpenDental, Twilio, Plaud. All three are
 * thin facades over {@link CredentialsService}; this exercises their write +
 * read + status surface against an in-memory H2 (MySQL-compat mode) DB.
 */
class Phase2ResolversTest {

    private static final String DB_NAME = "test_p2_" + UUID.randomUUID().toString().replace("-", "");
    private static final String JDBC = "jdbc:h2:mem:" + DB_NAME + ";MODE=MYSQL;DB_CLOSE_DELAY=-1";
    private static final String MASTER_KEY_B64 = Base64.getEncoder().encodeToString(new byte[32]);

    private TenantDataSources stubDs;
    private CredentialsService creds;

    @BeforeAll
    static void initSchema() throws Exception {
        try (Connection c = DriverManager.getConnection(JDBC); var s = c.createStatement()) {
            s.executeUpdate(
                "CREATE TABLE tenant_credentials (\n"
              + "    id BIGINT AUTO_INCREMENT PRIMARY KEY,\n"
              + "    provider VARCHAR(64) NOT NULL,\n"
              + "    key_name VARCHAR(64) NOT NULL,\n"
              + "    value_ciphertext VARBINARY(2048),\n"
              + "    value_iv VARBINARY(12),\n"
              + "    value_tag VARBINARY(16),\n"
              + "    use_platform_default BOOLEAN NOT NULL DEFAULT FALSE,\n"
              + "    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,\n"
              + "    updated_by_email VARCHAR(255),\n"
              + "    updated_by_role VARCHAR(32),\n"
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
        creds = new CredentialsService(stubDs, MASTER_KEY_B64, "", new MockEnvironment());
    }

    // ─── OpenDental ────────────────────────────────────────────────────────

    @Test
    void opendentalUpdateAndResolve() {
        OpenDentalKeyResolver r = new OpenDentalKeyResolver(creds);
        r.update("any-db", "DEV-123", "CUST-456", "alice@x", "tenant_user");

        OpenDentalKeyResolver.Keys k = r.resolveForDb("any-db");
        assertThat(k.developerKey()).isEqualTo("DEV-123");
        assertThat(k.customerKey()).isEqualTo("CUST-456");
        assertThat(k.isComplete()).isTrue();

        OpenDentalKeyResolver.Status s = r.statusForDb("any-db");
        assertThat(s.hasDeveloperKey()).isTrue();
        assertThat(s.hasCustomerKey()).isTrue();
        assertThat(s.isComplete()).isTrue();
    }

    @Test
    void opendentalClearOneKeyKeepsTheOther() {
        OpenDentalKeyResolver r = new OpenDentalKeyResolver(creds);
        r.update("any-db", "DEV", "CUST", null, "super_admin");
        r.update("any-db", "", null, null, "super_admin"); // clear dev only

        OpenDentalKeyResolver.Status s = r.statusForDb("any-db");
        assertThat(s.hasDeveloperKey()).isFalse();
        assertThat(s.hasCustomerKey()).isTrue();
    }

    // ─── Twilio ────────────────────────────────────────────────────────────

    @Test
    void twilioUpdateAndResolve() {
        TwilioCredentialsResolver r = new TwilioCredentialsResolver(creds);
        r.update("any-db", "AC123", "tok-abc", "+15555550100", null, "super_admin");

        TwilioCredentialsResolver.Credentials c = r.resolveForDb("any-db");
        assertThat(c.accountSid()).isEqualTo("AC123");
        assertThat(c.authToken()).isEqualTo("tok-abc");
        assertThat(c.fromNumber()).isEqualTo("+15555550100");
        assertThat(c.isComplete()).isTrue();
    }

    @Test
    void twilioBlankAuthTokenLeavesItUntouched() {
        TwilioCredentialsResolver r = new TwilioCredentialsResolver(creds);
        r.update("any-db", "AC1", "tok1", "+1", null, "super_admin");
        // Re-update with blank authToken — original should survive.
        r.update("any-db", "AC2", "", "+2", null, "super_admin");
        TwilioCredentialsResolver.Credentials c = r.resolveForDb("any-db");
        assertThat(c.accountSid()).isEqualTo("AC2");
        assertThat(c.authToken()).isEqualTo("tok1");
        assertThat(c.fromNumber()).isEqualTo("+2");
    }

    @Test
    void twilioStatusReportsEachField() {
        TwilioCredentialsResolver r = new TwilioCredentialsResolver(creds);
        r.update("any-db", "AC1", "tok", null, null, "super_admin");
        TwilioCredentialsResolver.Status s = r.statusForDb("any-db");
        assertThat(s.hasAccountSid()).isTrue();
        assertThat(s.hasAuthToken()).isTrue();
        assertThat(s.hasFromNumber()).isFalse();
        assertThat(s.isComplete()).isFalse();
    }

    @Test
    void twilioClearAllWipesEveryField_includingAuthTokenWhichBlankPutCannotClear() {
        // The PUT path treats blank authToken as "leave untouched" so it
        // can't be used to wipe a key. clearAll exists specifically to
        // bypass that — admin "Clear" button hits DELETE which calls this.
        TwilioCredentialsResolver r = new TwilioCredentialsResolver(creds);
        r.update("any-db", "AC1", "secret", "+15551110000", null, "super_admin");
        TwilioCredentialsResolver.Status before = r.statusForDb("any-db");
        assertThat(before.isComplete()).isTrue();

        r.clearAll("any-db", null, "super_admin");

        TwilioCredentialsResolver.Status after = r.statusForDb("any-db");
        assertThat(after.hasAccountSid()).isFalse();
        assertThat(after.hasAuthToken()).isFalse();
        assertThat(after.hasFromNumber()).isFalse();
    }

    // ─── Plaud ─────────────────────────────────────────────────────────────

    @Test
    void plaudUpdateAndResolve() {
        PlaudKeyResolver r = new PlaudKeyResolver(creds);
        r.update("any-db", "eyJ.bearer", null, "super_admin");
        assertThat(r.resolveForDb("any-db")).isEqualTo("eyJ.bearer");
        assertThat(r.statusForDb("any-db").hasToken()).isTrue();
    }

    @Test
    void plaudClearViaEmptyString() {
        PlaudKeyResolver r = new PlaudKeyResolver(creds);
        r.update("any-db", "x", null, "super_admin");
        r.update("any-db", "", null, "super_admin");
        assertThat(r.statusForDb("any-db").hasToken()).isFalse();
    }

    // ─── ZoomPhone ─────────────────────────────────────────────────────────

    @Test
    void zoomPhone_roundTrip() {
        ZoomPhoneCredentialsResolver r = new ZoomPhoneCredentialsResolver(creds);
        r.update("any-db", "acct-1", "cid-1", "csec-1", "+15550001111", null, "super_admin");

        ZoomPhoneCredentialsResolver.Credentials c = r.resolveForDb("any-db");
        assertThat(c.accountId()).isEqualTo("acct-1");
        assertThat(c.clientId()).isEqualTo("cid-1");
        assertThat(c.clientSecret()).isEqualTo("csec-1");
        assertThat(c.fromNumber()).isEqualTo("+15550001111");
        assertThat(c.isComplete()).isTrue();
    }

    @Test
    void zoomPhone_status_partial() {
        ZoomPhoneCredentialsResolver r = new ZoomPhoneCredentialsResolver(creds);
        r.update("any-db", "acct-1", null, null, null, null, "super_admin");

        ZoomPhoneCredentialsResolver.Status s = r.statusForDb("any-db");
        assertThat(s.hasAccountId()).isTrue();
        assertThat(s.hasClientId()).isFalse();
        assertThat(s.hasClientSecret()).isFalse();
        assertThat(s.hasFromNumber()).isFalse();
        assertThat(s.isComplete()).isFalse();
    }

    @Test
    void zoomPhone_clearAll_wipesAllFields() {
        ZoomPhoneCredentialsResolver r = new ZoomPhoneCredentialsResolver(creds);
        r.update("any-db", "acct-1", "cid-1", "csec-1", "+15550001111", null, "super_admin");
        assertThat(r.statusForDb("any-db").isComplete()).isTrue();

        r.clearAll("any-db", null, "super_admin");

        ZoomPhoneCredentialsResolver.Status s = r.statusForDb("any-db");
        assertThat(s.hasAccountId()).isFalse();
        assertThat(s.hasClientId()).isFalse();
        assertThat(s.hasClientSecret()).isFalse();
        assertThat(s.hasFromNumber()).isFalse();
    }

    @Test
    void zoomPhone_blankClientSecret_leavesExisting() {
        ZoomPhoneCredentialsResolver r = new ZoomPhoneCredentialsResolver(creds);
        r.update("any-db", "acct-1", "cid-1", "original-secret", "+15550001111", null, "super_admin");
        // Re-update with blank clientSecret — original should survive.
        r.update("any-db", "acct-2", "cid-2", "", "+15550002222", null, "super_admin");

        ZoomPhoneCredentialsResolver.Credentials c = r.resolveForDb("any-db");
        assertThat(c.clientSecret()).isEqualTo("original-secret");
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
