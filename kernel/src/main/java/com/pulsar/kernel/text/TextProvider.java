package com.pulsar.kernel.text;

import javax.sql.DataSource;

/**
 * Per-tenant text/SMS provider integration. One implementation per vendor;
 * Spring beans, registered in {@link TextProviderRegistry}. Mirrors
 * {@link com.pulsar.kernel.voice.VoiceProvider} for the text track.
 *
 * <p>Most tenants will use the same vendor for voice and text (RingCentral
 * Advanced is one bundle); credentials live in the shared
 * {@code voice_provider_config} table to avoid double-entry.
 */
public interface TextProvider {

    String id();

    String label();

    /** Default sender phone configured for this tenant (E.164). */
    String defaultFromPhone(DataSource tenantDs);
}
