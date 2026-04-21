package com.pulsar.kernel.security;

import com.pulsar.kernel.auth.JwtService;
import com.pulsar.kernel.auth.Principal;
import com.pulsar.kernel.auth.PrincipalContext;
import com.pulsar.kernel.tenant.TenantContext;
import com.pulsar.kernel.tenant.TenantInfo;
import com.pulsar.kernel.tenant.TenantLookupService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtFilter extends OncePerRequestFilter {
    private final JwtService jwt;
    private final TenantLookupService tenants;

    public JwtFilter(JwtService jwt, TenantLookupService tenants) {
        this.jwt = jwt;
        this.tenants = tenants;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
        throws ServletException, IOException {
        String header = req.getHeader("Authorization");
        String slug = null;
        if (header != null && header.startsWith("Bearer ")) {
            try {
                slug = apply(header.substring(7));
            } catch (Exception e) {
                res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                res.setContentType("application/json");
                res.getWriter().write("{\"error\":\"invalid_token\"}");
                return;
            }
        }
        if (slug != null) {
            MDC.put("tenant_id", slug);
        }
        MDC.put("session_id",
            Optional.ofNullable(req.getHeader("X-Request-Id")).orElseGet(() -> UUID.randomUUID().toString()));
        try {
            chain.doFilter(req, res);
        } finally {
            PrincipalContext.clear();
            TenantContext.clear();
            MDC.remove("tenant_id");
            MDC.remove("session_id");
        }
    }

    private String apply(String token) {
        Claims c = jwt.parse(token);
        String role = c.get("role", String.class);
        if ("super_admin".equals(role)) {
            PrincipalContext.set(new Principal.Admin());
            return null;
        } else if ("tenant_user".equals(role)) {
            String slug = c.get("slug", String.class);
            String email = c.get("email", String.class);
            PrincipalContext.set(new Principal.TenantUser(slug, email));
            tenants.bySlug(slug).ifPresent(t ->
                TenantContext.set(new TenantInfo(t.id(), t.slug(), t.name(), t.dbName(), t.activeModules(), t.suspended()))
            );
            return slug;
        }
        return null;
    }
}
