package com.pulsar.opendentalcalendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pulsar.kernel.credentials.TwilioCredentialsResolver;
import com.pulsar.kernel.credentials.ZoomPhoneCredentialsResolver;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class SmsDispatcherTest {

    private static final String DB = "test_db";
    private static final String TO = "+15550001111";
    private static final String BODY = "Hello!";

    private final OdCalendarSmsClient twilioClient = mock(OdCalendarSmsClient.class);
    private final ZoomPhoneSmsClient zoomClient = mock(ZoomPhoneSmsClient.class);
    private final TwilioCredentialsResolver twilioRes = mock(TwilioCredentialsResolver.class);
    private final ZoomPhoneCredentialsResolver zoomRes = mock(ZoomPhoneCredentialsResolver.class);

    private final SmsDispatcher dispatcher =
            new SmsDispatcher(twilioClient, zoomClient, twilioRes, zoomRes);

    // ── complete credentials helpers ──────────────────────────────────────────

    private static ZoomPhoneCredentialsResolver.Credentials zoomComplete() {
        return new ZoomPhoneCredentialsResolver.Credentials("ACC", "CLI", "SEC", "+1");
    }

    private static ZoomPhoneCredentialsResolver.Credentials zoomIncomplete() {
        return new ZoomPhoneCredentialsResolver.Credentials(null, null, null, null);
    }

    private static TwilioCredentialsResolver.Credentials twilioComplete() {
        return new TwilioCredentialsResolver.Credentials("AC123", "tok", "+1");
    }

    private static TwilioCredentialsResolver.Credentials twilioIncomplete() {
        return new TwilioCredentialsResolver.Credentials(null, null, null);
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    @Test
    void usesZoomWhenZoomComplete() throws IOException {
        when(zoomRes.resolveForDb(DB)).thenReturn(zoomComplete());
        when(twilioRes.resolveForDb(DB)).thenReturn(twilioComplete());

        dispatcher.send(DB, TO, BODY);

        verify(zoomClient).send("ACC", "CLI", "SEC", "+1", TO, BODY);
        verify(twilioClient, never()).send(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void fallsBackToTwilioWhenZoomIncomplete() throws IOException {
        when(zoomRes.resolveForDb(DB)).thenReturn(zoomIncomplete());
        when(twilioRes.resolveForDb(DB)).thenReturn(twilioComplete());

        dispatcher.send(DB, TO, BODY);

        verify(twilioClient).send("AC123", "tok", "+1", TO, BODY);
        verify(zoomClient, never()).send(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void throwsWhenNeitherConfigured() {
        when(zoomRes.resolveForDb(DB)).thenReturn(zoomIncomplete());
        when(twilioRes.resolveForDb(DB)).thenReturn(twilioIncomplete());

        assertThatThrownBy(() -> dispatcher.send(DB, TO, BODY))
                .isInstanceOf(SmsDispatcher.SmsNotConfiguredException.class)
                .hasMessage("sms_not_configured");
    }

    @Test
    void activeProvider_zoom() {
        when(zoomRes.resolveForDb(DB)).thenReturn(zoomComplete());

        assertThat(dispatcher.activeProvider(DB)).isEqualTo("zoom-phone");
    }

    @Test
    void activeProvider_twilio() {
        when(zoomRes.resolveForDb(DB)).thenReturn(zoomIncomplete());
        when(twilioRes.resolveForDb(DB)).thenReturn(twilioComplete());

        assertThat(dispatcher.activeProvider(DB)).isEqualTo("twilio");
    }

    @Test
    void activeProvider_none() {
        when(zoomRes.resolveForDb(DB)).thenReturn(zoomIncomplete());
        when(twilioRes.resolveForDb(DB)).thenReturn(twilioIncomplete());

        assertThat(dispatcher.activeProvider(DB)).isEqualTo("none");
    }

    // ── helper for varargs-style never() calls ────────────────────────────────

    private static String anyString() {
        return org.mockito.ArgumentMatchers.anyString();
    }
}
