package com.pulsar.kernel.tenant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Pins the second-line-of-defense slug check inside
 * {@link TenantProvisioningService#create}. The same shape is enforced
 * at the API boundary via Bean Validation; this guards against any
 * internal caller that bypasses the DTO.
 *
 * <p>We don't construct a real {@link TenantProvisioningService} here
 * (it requires a live datasource for {@code createDatabase}) — the
 * regex check fires before any I/O, so triggering with nulls for the
 * unused dependencies is sufficient to assert it throws BEFORE Hikari
 * is touched.
 */
class TenantProvisioningServiceSlugTest {

    private static TenantProvisioningService svc() {
        return new TenantProvisioningService(
            "jdbc:mysql://unused", "root", "pulsar", null, null, null, null
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "",                  // empty
        "a",                 // too short (one char total — regex needs 1 leading + 1+ more = 2+)
        "A",                 // uppercase start
        "Acme-dental",       // uppercase
        "1acme",             // digit start
        "-acme",             // hyphen start
        "acme.dental",       // dot
        "acme/dental",       // slash
        "acme dental",       // space
        "acmé-dental",       // non-ASCII
    })
    void rejectsBadSlugs(String bad) {
        assertThatThrownBy(() -> svc().create(bad, "name", "x@example.com", "PULS-DEV-0000"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("invalid slug");
    }

    @org.junit.jupiter.api.Test
    void rejectsNullSlug() {
        assertThatThrownBy(() -> svc().create(null, "name", "x@example.com", "PULS-DEV-0000"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
