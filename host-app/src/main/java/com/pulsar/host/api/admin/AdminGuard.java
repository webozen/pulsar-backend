package com.pulsar.host.api.admin;

import com.pulsar.kernel.auth.Principal;
import com.pulsar.kernel.auth.PrincipalContext;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

final class AdminGuard {
    private AdminGuard() {}

    static void requireAdmin() {
        Principal p = PrincipalContext.get();
        if (!(p instanceof Principal.Admin)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "admin_required");
        }
    }
}
