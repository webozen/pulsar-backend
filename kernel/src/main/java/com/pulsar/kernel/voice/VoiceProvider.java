package com.pulsar.kernel.voice;

import javax.sql.DataSource;

/**
 * Per-tenant voice provider integration. Each implementation maps one VoIP
 * vendor (RingCentral, Twilio Flex, Zoom Phone, …) to the Pulsar voice modules.
 * Implementations are Spring beans; their {@link #id()} is how the registry
 * routes traffic and how callers ask "give me the active provider for this
 * tenant".
 *
 * <p>The provider does NOT know about specific consumer modules — it only
 * exposes the surfaces consumers need (a softphone embed URL, whether live
 * media is reachable from the browser, etc.).
 */
public interface VoiceProvider {

    /** Stable identifier used in URL paths, DB rows, and the registry. */
    String id();

    /** Human-readable label for admin UIs. */
    String label();

    /**
     * URL of the browser-embeddable softphone for this tenant. Implementations
     * read per-tenant overrides from their own config table; pass the tenant
     * datasource so they can do that lookup.
     */
    String embedUrl(DataSource tenantDs);

    /**
     * Whether this provider gives the browser direct access to the remote
     * (patient) audio track. Iframe-based softphones can't (cross-origin
     * peer connection); SDK-mounted ones can. Co-pilot uses this flag to
     * decide whether to open one or two transcription channels.
     *
     * <p>The {@link #supportsLiveMedia(DataSource)} overload lets a provider
     * answer per-tenant (e.g. RingCentral can be in either iframe or SDK mode
     * depending on a tenant config flag). The no-arg version stays for
     * backward compat and defaults to delegating to a null DataSource.
     */
    default boolean supportsLiveMedia(DataSource tenantDs) { return supportsLiveMedia(); }

    boolean supportsLiveMedia();
}
