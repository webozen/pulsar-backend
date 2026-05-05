package com.pulsar.host.api.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pulsar.kernel.auth.Principal;
import com.pulsar.kernel.auth.PrincipalContext;
import com.pulsar.kernel.credentials.CredentialsService;
import com.pulsar.kernel.credentials.GeminiKeyResolver;
import com.pulsar.kernel.credentials.OpenDentalKeyResolver;
import com.pulsar.kernel.credentials.PlaudKeyResolver;
import com.pulsar.kernel.credentials.TwilioCredentialsResolver;
import com.pulsar.kernel.credentials.ZoomPhoneCredentialsResolver;
import com.pulsar.kernel.tenant.TenantRecord;
import com.pulsar.kernel.tenant.TenantRepository;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/**
 * Unit tests for {@link AdminApiKeysController#reveal}. Covers the new
 * super-admin "show me the actual stored value" endpoint that decrypts on
 * demand. Privacy-sensitive: the controller must reject non-admin callers
 * and 404 cleanly when a tenant or key isn't found.
 */
class AdminApiKeysRevealTest {

    private TenantRepository tenantRepo;
    private CredentialsService creds;
    private AdminApiKeysController controller;

    @BeforeEach
    void setUp() {
        tenantRepo = mock(TenantRepository.class);
        creds = mock(CredentialsService.class);
        controller = new AdminApiKeysController(
            tenantRepo,
            mock(GeminiKeyResolver.class),
            mock(OpenDentalKeyResolver.class),
            mock(TwilioCredentialsResolver.class),
            mock(PlaudKeyResolver.class),
            mock(ZoomPhoneCredentialsResolver.class),
            creds
        );
        PrincipalContext.set(new Principal.Admin());
    }

    @AfterEach
    void tearDown() { PrincipalContext.clear(); }

    @Test
    void revealReturnsDecryptedValueForAdmin() {
        when(tenantRepo.findById(7L))
            .thenReturn(Optional.of(new TenantRecord(
                7L, "growingsmiles", "Growing Smiles", "pulsar_t_growingsmiles",
                java.util.Set.of(), "owner@growingsmiles.com", "$2a$10$bcrypt",
                null, null, java.time.Instant.now())));
        when(creds.resolve(eq("pulsar_t_growingsmiles"), eq("opendental"), eq("developer_key")))
            .thenReturn(new CredentialsService.Resolution("kTe1ihtNVZrnOb5L", CredentialsService.Source.TENANT));

        var resp = controller.reveal(7L, "opendental", "developer_key");

        assertThat(resp).containsEntry("value", "kTe1ihtNVZrnOb5L");
        assertThat(resp).containsEntry("source", "TENANT");
    }

    @Test
    void revealReturnsNullValueWhenKeyNotConfigured() {
        when(tenantRepo.findById(7L))
            .thenReturn(Optional.of(new TenantRecord(
                7L, "x", "X", "pulsar_t_x", java.util.Set.of(), null, "h", null, null, java.time.Instant.now())));
        when(creds.resolve(any(), any(), any()))
            .thenReturn(new CredentialsService.Resolution(null, CredentialsService.Source.NONE));

        var resp = controller.reveal(7L, "gemini", "api_key");

        assertThat(resp.get("value")).isNull();
        assertThat(resp).containsEntry("source", "NONE");
    }

    @Test
    void revealRejectsNonAdminCaller() {
        PrincipalContext.set(new Principal.TenantUser("growingsmiles", "user@example.com"));

        assertThatThrownBy(() -> controller.reveal(7L, "opendental", "developer_key"))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("admin_required");
    }

    @Test
    void revealReturns404ForUnknownTenant() {
        when(tenantRepo.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.reveal(99L, "opendental", "developer_key"))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("tenant_not_found");
    }

    @Test
    void revealRejectsUnknownProvider() {
        when(tenantRepo.findById(7L))
            .thenReturn(Optional.of(new TenantRecord(
                7L, "x", "X", "pulsar_t_x", java.util.Set.of(), null, "h", null, null, java.time.Instant.now())));

        assertThatThrownBy(() -> controller.reveal(7L, "totally-bogus", "key"))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("unknown_provider");
    }
}
