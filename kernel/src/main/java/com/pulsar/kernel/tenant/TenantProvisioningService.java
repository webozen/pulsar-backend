package com.pulsar.kernel.tenant;

import java.sql.Connection;
import java.sql.Statement;
import java.security.SecureRandom;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Service;

@Service
public class TenantProvisioningService {
    private static final SecureRandom RNG = new SecureRandom();
    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    private final String rootUrl;
    private final String user;
    private final String password;
    private final TenantRepository repo;
    private final MigrationRunner migrations;

    public TenantProvisioningService(
        @Value("${pulsar.mysql.base-jdbc-url}") String baseUrl,
        @Value("${pulsar.mysql.user}") String user,
        @Value("${pulsar.mysql.password}") String password,
        TenantRepository repo,
        MigrationRunner migrations
    ) {
        this.rootUrl = baseUrl + "/?useSSL=false&allowPublicKeyRetrieval=true";
        this.user = user;
        this.password = password;
        this.repo = repo;
        this.migrations = migrations;
    }

    public TenantRecord create(String slug, String name, String contactEmail, String passcode) {
        String dbName = "pulsar_t_" + slug.replace('-', '_');
        createDatabase(dbName);
        long id = repo.insert(slug, name, dbName, contactEmail, passcode);
        TenantRecord rec = repo.findById(id).orElseThrow();
        migrations.migrateTenant(dbName, Set.of());
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
