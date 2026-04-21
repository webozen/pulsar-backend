package com.pulsar.host.api.admin;

import com.pulsar.kernel.auth.JwtService;
import com.pulsar.kernel.module.ModuleRegistry;
import com.pulsar.kernel.tenant.MigrationRunner;
import com.pulsar.kernel.tenant.TenantLookupService;
import com.pulsar.kernel.tenant.TenantProvisioningService;
import com.pulsar.kernel.tenant.TenantRecord;
import com.pulsar.kernel.tenant.TenantRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/admin/tenants")
public class AdminTenantsController {
    private final TenantRepository repo;
    private final TenantProvisioningService provisioning;
    private final TenantLookupService lookup;
    private final ModuleRegistry modules;
    private final MigrationRunner migrations;
    private final JwtService jwt;

    public AdminTenantsController(
        TenantRepository repo,
        TenantProvisioningService provisioning,
        TenantLookupService lookup,
        ModuleRegistry modules,
        MigrationRunner migrations,
        JwtService jwt
    ) {
        this.repo = repo;
        this.provisioning = provisioning;
        this.lookup = lookup;
        this.modules = modules;
        this.migrations = migrations;
        this.jwt = jwt;
    }

    public record CreateTenantRequest(
        @NotBlank @Pattern(regexp = "^[a-z][a-z0-9-]{1,62}$") String slug,
        @NotBlank String name,
        @NotBlank @Email String contactEmail
    ) {}

    public record CreateTenantResponse(TenantDto tenant, String passcode) {}

    public record ModulesRequest(@Valid Set<String> modules) {}

    @GetMapping
    public List<TenantDto> list() {
        AdminGuard.requireAdmin();
        return repo.findAll().stream().map(TenantDto::of).toList();
    }

    @PostMapping
    public ResponseEntity<CreateTenantResponse> create(@Valid @RequestBody CreateTenantRequest req) {
        AdminGuard.requireAdmin();
        if (repo.slugExists(req.slug())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "slug_taken");
        }
        String passcode = TenantProvisioningService.generatePasscode();
        TenantRecord rec = provisioning.create(req.slug(), req.name(), req.contactEmail(), passcode);
        lookup.invalidate(req.slug());
        return ResponseEntity.ok(new CreateTenantResponse(TenantDto.of(rec), passcode));
    }

    @PatchMapping("/{id}/modules")
    public TenantDto updateModules(@PathVariable long id, @RequestBody ModulesRequest req) {
        AdminGuard.requireAdmin();
        TenantRecord existing = repo.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        Set<String> requested = req.modules() == null ? Set.of() : req.modules();
        for (String m : requested) {
            if (!modules.exists(m)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown_module:" + m);
        }
        Set<String> added = new HashSet<>(requested);
        added.removeAll(existing.activeModules());
        repo.updateModules(id, requested);
        for (String m : added) {
            migrations.migrateModule(existing.dbName(), modules.get(m));
        }
        lookup.invalidate(existing.slug());
        return TenantDto.of(repo.findById(id).orElseThrow());
    }

    @PostMapping("/{id}/passcode")
    public Map<String, String> regeneratePasscode(@PathVariable long id) {
        AdminGuard.requireAdmin();
        TenantRecord existing = repo.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        String passcode = TenantProvisioningService.generatePasscode();
        repo.updatePasscode(id, passcode);
        lookup.invalidate(existing.slug());
        return Map.of("passcode", passcode);
    }

    @PostMapping("/{id}/impersonate")
    public ResponseEntity<Map<String, String>> impersonate(@PathVariable long id, HttpServletRequest httpReq) {
        AdminGuard.requireAdmin();
        TenantRecord existing = repo.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (existing.suspended()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "tenant_suspended");
        }
        String email = existing.contactEmail() == null || existing.contactEmail().isBlank()
            ? "admin@pulsar"
            : existing.contactEmail();
        String token = jwt.issueTenant(existing.slug(), email);

        ResponseCookie cookie = ResponseCookie.from("pulsar_jwt", token)
            .httpOnly(true)
            .path("/")
            .sameSite("Lax")
            .secure(isHttps(httpReq))
            .maxAge(Duration.ofSeconds(jwt.getTtlSeconds()))
            .build();
        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, cookie.toString())
            .body(Map.of("token", token));
    }

    private static boolean isHttps(HttpServletRequest req) {
        String fwd = req.getHeader("X-Forwarded-Proto");
        if (fwd != null && !fwd.isBlank()) {
            return "https".equalsIgnoreCase(fwd.split(",")[0].trim());
        }
        return "https".equalsIgnoreCase(req.getScheme());
    }

    @PatchMapping("/{id}/suspend")
    public TenantDto suspend(@PathVariable long id) {
        AdminGuard.requireAdmin();
        TenantRecord existing = repo.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        repo.setSuspended(id, true);
        lookup.invalidate(existing.slug());
        return TenantDto.of(repo.findById(id).orElseThrow());
    }

    @PatchMapping("/{id}/resume")
    public TenantDto resume(@PathVariable long id) {
        AdminGuard.requireAdmin();
        TenantRecord existing = repo.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        repo.setSuspended(id, false);
        lookup.invalidate(existing.slug());
        return TenantDto.of(repo.findById(id).orElseThrow());
    }

    public record ModuleCatalogEntry(String id, String title, String tagline, String icon, String category) {}

    @GetMapping("/modules/catalog")
    public List<ModuleCatalogEntry> catalog() {
        AdminGuard.requireAdmin();
        return modules.all().stream()
            .map(m -> new ModuleCatalogEntry(
                m.id(),
                m.manifest().title(),
                m.manifest().tagline(),
                m.manifest().icon(),
                m.manifest().category()))
            .collect(Collectors.toList());
    }
}
