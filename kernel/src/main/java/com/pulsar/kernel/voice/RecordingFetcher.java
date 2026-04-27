package com.pulsar.kernel.voice;

import java.io.IOException;
import javax.sql.DataSource;

/**
 * Provider-specific call recording downloader. Each provider stores its
 * recordings differently (RC = signed URL behind OAuth, Twilio = basic-auth'd
 * .mp3 link, Zoom = bearer-token JSON envelope, …). Implementations read the
 * tenant's stored credentials from their own per-tenant config table.
 */
public interface RecordingFetcher {

    /** Stable provider id, must match {@link VoiceProvider#id()}. */
    String id();

    /** Default content-type to assume when the provider doesn't return one. */
    default String defaultMimeType() { return "audio/mpeg"; }

    /** Download the audio bytes referenced by {@code recordingRef}. */
    byte[] fetch(String recordingRef, DataSource tenantDs) throws IOException;
}
