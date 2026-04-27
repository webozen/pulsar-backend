package com.pulsar.kernel.tenant;

import java.sql.Connection;
import java.sql.Statement;
import java.security.SecureRandom;
import java.util.Set;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import com.pulsar.kernel.tenant.events.TenantEvents;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class TenantProvisioningService {
    private static final SecureRandom RNG = new SecureRandom();
    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    /**
     * Slug shape mirror of the Bean Validation pattern on the public API
     * DTO ({@code AdminTenantsController.CreateTenantRequest}). Re-asserted
     * here as a second line of defense — any internal caller that reaches
     * {@link #create} with a malformed slug fails fast before it becomes
     * a corrupted MySQL identifier via {@code "pulsar_t_" + slug.replace('-','_')}.
     */
    private static final Pattern SLUG_PATTERN = Pattern.compile("^[a-z][a-z0-9-]{1,62}$");

    private final String rootUrl;
    private final String user;
    private final String password;
    private final TenantRepository repo;
    private final MigrationRunner migrations;
    private final BCryptPasswordEncoder encoder;
    private final ApplicationEventPublisher events;

    public TenantProvisioningService(
        @Value("${pulsar.mysql.base-jdbc-url}") String baseUrl,
        @Value("${pulsar.mysql.user}") String user,
        @Value("${pulsar.mysql.password}") String password,
        TenantRepository repo,
        MigrationRunner migrations,
        BCryptPasswordEncoder encoder,
        ApplicationEventPublisher events
    ) {
        this.rootUrl = baseUrl + "/?useSSL=false&allowPublicKeyRetrieval=true";
        this.user = user;
        this.password = password;
        this.repo = repo;
        this.migrations = migrations;
        this.encoder = encoder;
        this.events = events;
    }

    /** @param plaintextPasscode The plaintext passcode. It is BCrypt-hashed before being written to the DB. */
    public TenantRecord create(String slug, String name, String contactEmail, String plaintextPasscode) {
        if (slug == null || !SLUG_PATTERN.matcher(slug).matches()) {
            throw new IllegalArgumentException("invalid slug: " + slug);
        }
        String dbName = "pulsar_t_" + slug.replace('-', '_');
        createDatabase(dbName);
        long id = repo.insert(slug, name, dbName, contactEmail, encoder.encode(plaintextPasscode));
        TenantRecord rec = repo.findById(id).orElseThrow();
        migrations.migrateTenant(dbName, Set.of());
        events.publishEvent(new TenantEvents.TenantCreated(id, slug, name, contactEmail));
        return rec;
    }

    public static String generatePasscode() {
        StringBuilder sb = new StringBuilder("PULS-");
        for (int i = 0; i < 4; i++) sb.append(ALPHABET[RNG.nextInt(ALPHABET.length)]);
        sb.append('-');
        for (int i = 0; i < 4; i++) sb.append(ALPHABET[RNG.nextInt(ALPHABET.length)]);
        return sb.toString();
    }

    private void createDatabase(String dbName) {
        DataSource ds = new DriverManagerDataSource(rootUrl, user, password);
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("CREATE DATABASE IF NOT EXISTS `" + dbName + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        } catch (Exception e) {
            throw new RuntimeException("Failed to create tenant database " + dbName, e);
        }
    }
}
