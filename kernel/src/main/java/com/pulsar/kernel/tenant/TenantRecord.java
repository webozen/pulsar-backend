package com.pulsar.kernel.tenant;

import java.time.Instant;
import java.util.Set;

/**
 * A platform-level tenant row.
 *
 * <p>{@code passcodeHash} is a BCrypt hash of the tenant's access passcode; the
 * plaintext is never stored or returned after creation. Use
 * {@link com.pulsar.kernel.auth.PasscodeAuthService} (which holds the
 * {@link org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder}) to
 * verify login attempts against this value.
 */
public record TenantRecord(
    long id,
    String slug,
    String name,
    String dbName,
    Set<String> activeModules,
    String contactEmail,
    String passcodeHash,
    Instant suspendedAt,
    Instant createdAt
) {
    public boolean suspended() { return suspendedAt != null; }
}
