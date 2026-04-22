package com.pulsar.host.startup;

import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * One-shot, idempotent backfill: any {@code public_tenants.access_passcode_hash}
 * that doesn't look like a BCrypt hash ($2a$/$2b$/$2y$) is treated as a plaintext
 * passcode left over from the pre-hashing era and is rehashed in place.
 *
 * <p>Runs early enough to beat {@link TenantMigrationSweeper}, which calls
 * through to the same DB but doesn't depend on passcode hashes.
 */
@Component
@Order(0)
public class PasscodeBackfillRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(PasscodeBackfillRunner.class);

    private final JdbcTemplate jdbc;
    private final BCryptPasswordEncoder encoder;

    public PasscodeBackfillRunner(DataSource dataSource, BCryptPasswordEncoder encoder) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.encoder = encoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        var plaintextRows = jdbc.query(
            "SELECT id, access_passcode_hash FROM public_tenants " +
            "WHERE access_passcode_hash NOT LIKE '$2a$%' " +
            "  AND access_passcode_hash NOT LIKE '$2b$%' " +
            "  AND access_passcode_hash NOT LIKE '$2y$%'",
            (rs, i) -> new long[]{ rs.getLong("id") }
        );
        if (plaintextRows.isEmpty()) return;

        log.info("[PasscodeBackfill] {} tenant row(s) still hold plaintext passcodes — rehashing now.",
            plaintextRows.size());

        for (long[] row : plaintextRows) {
            long id = row[0];
            // Reload to fetch the plaintext value (kept separate from the batch to avoid
            // streaming plaintexts through logs / query plans).
            String plaintext = jdbc.queryForObject(
                "SELECT access_passcode_hash FROM public_tenants WHERE id = ?",
                String.class,
                id
            );
            if (plaintext == null || plaintext.isEmpty()) continue;
            String hashed = encoder.encode(plaintext);
            jdbc.update("UPDATE public_tenants SET access_passcode_hash = ? WHERE id = ?", hashed, id);
        }
        log.info("[PasscodeBackfill] done.");
    }
}
