package com.pulsar.kernel.auth;

import com.pulsar.kernel.tenant.TenantLookupService;
import com.pulsar.kernel.tenant.TenantRecord;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class PasscodeAuthService {
    private final String adminPasscode;
    private final TenantLookupService tenants;

    public PasscodeAuthService(
        @Value("${pulsar.admin.passcode}") String adminPasscode,
        TenantLookupService tenants
    ) {
        this.adminPasscode = adminPasscode;
        this.tenants = tenants;
    }

    public boolean verifyAdmin(String passcode) {
        return passcode != null && constantTimeEquals(passcode, adminPasscode);
    }

    public Optional<TenantRecord> verifyTenant(String slug, String passcode) {
        if (passcode == null) return Optional.empty();
        return tenants.bySlug(slug)
            .filter(t -> !t.suspended())
            .filter(t -> constantTimeEquals(passcode, t.passcode()));
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        if (a.length() != b.length()) return false;
        int r = 0;
        for (int i = 0; i < a.length(); i++) r |= a.charAt(i) ^ b.charAt(i);
        return r == 0;
    }
}
