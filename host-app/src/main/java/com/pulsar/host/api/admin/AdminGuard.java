package com.pulsar.host.api.admin;

import com.pulsar.kernel.auth.Principal;
// Exposed publicly so callers outside the admin package (e.g. BrandingController's
// admin-only endpoint) can reuse the same admin-role check without duplicating logic.
import com.pulsar.kernel.auth.PrincipalContext;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

public final class AdminGuard {
    private AdminGuard() {}

    public static void requireAdmin() {
        Principal p = PrincipalContext.get();
        if (!(p instanceof Principal.Admin)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "admin_required");
        }
    }
}
