package com.pulsar.kernel.auth;

import com.pulsar.kernel.tenant.TenantLookupService;
import com.pulsar.kernel.tenant.TenantRecord;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class PasscodeAuthService {
    private final String adminPasscode;
    private final TenantLookupService tenants;
    private final BCryptPasswordEncoder encoder;

    public PasscodeAuthService(
        @Value("${pulsar.admin.passcode}") String adminPasscode,
        TenantLookupService tenants,
        BCryptPasswordEncoder encoder
    ) {
        this.adminPasscode = adminPasscode;
        this.tenants = tenants;
        this.encoder = encoder;
    }

    public boolean verifyAdmin(String passcode) {
        return passcode != null && constantTimeEquals(passcode, adminPasscode);
    }

    public Optional<TenantRecord> verifyTenant(String slug, String email, String passcode) {
        if (passcode == null || email == null) return Optional.empty();
        String normalizedEmail = email.trim();
        return tenants.bySlug(slug)
            .filter(t -> !t.suspended())
            // BCryptPasswordEncoder.matches() handles both valid bcrypt hashes (returns
            // the constant-time SHA result) and malformed hashes (returns false) — safe
            // to call on any column value.
            .filter(t -> t.passcodeHash() != null && encoder.matches(passcode, t.passcodeHash()))
            .filter(t -> t.contactEmail() != null
                && t.contactEmail().length() == normalizedEmail.length()
                && t.contactEmail().equalsIgnoreCase(normalizedEmail));
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        if (a.length() != b.length()) return false;
        int r = 0;
        for (int i = 0; i < a.length(); i++) r |= a.charAt(i) ^ b.charAt(i);
        return r == 0;
    }
}
