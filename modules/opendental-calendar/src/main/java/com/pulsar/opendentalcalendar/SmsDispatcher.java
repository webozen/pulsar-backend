package com.pulsar.opendentalcalendar;

import com.pulsar.kernel.credentials.TwilioCredentialsResolver;
import com.pulsar.kernel.credentials.ZoomPhoneCredentialsResolver;
import java.io.IOException;
import org.springframework.stereotype.Component;

@Component
public class SmsDispatcher {

    public static class SmsNotConfiguredException extends RuntimeException {
        public SmsNotConfiguredException() {
            super("sms_not_configured");
        }
    }

    private final OdCalendarSmsClient twilioClient;
    private final ZoomPhoneSmsClient zoomClient;
    private final TwilioCredentialsResolver twilioRes;
    private final ZoomPhoneCredentialsResolver zoomRes;

    public SmsDispatcher(
            OdCalendarSmsClient twilioClient,
            ZoomPhoneSmsClient zoomClient,
            TwilioCredentialsResolver twilioRes,
            ZoomPhoneCredentialsResolver zoomRes) {
        this.twilioClient = twilioClient;
        this.zoomClient   = zoomClient;
        this.twilioRes    = twilioRes;
        this.zoomRes      = zoomRes;
    }

    /**
     * Returns the name of the active SMS provider for the given tenant DB:
     * {@code "zoom-phone"}, {@code "twilio"}, or {@code "none"}.
     */
    public String activeProvider(String dbName) {
        if (zoomRes.resolveForDb(dbName).isComplete()) return "zoom-phone";
        if (twilioRes.resolveForDb(dbName).isComplete()) return "twilio";
        return "none";
    }

    /**
     * Returns the from-number of the active provider, or {@code null} if neither
     * is configured.
     */
    public String fromNumber(String dbName) {
        ZoomPhoneCredentialsResolver.Credentials zoom = zoomRes.resolveForDb(dbName);
        if (zoom.isComplete()) return zoom.fromNumber();

        TwilioCredentialsResolver.Credentials twilio = twilioRes.resolveForDb(dbName);
        if (twilio.isComplete()) return twilio.fromNumber();

        return null;
    }

    /**
     * Sends an SMS via whichever provider is fully configured (Zoom Phone
     * preferred, Twilio as fallback).
     *
     * @throws SmsNotConfiguredException if neither provider is configured
     * @throws IOException               on network / API failure
     */
    public void send(String dbName, String toNumber, String body) throws IOException {
        ZoomPhoneCredentialsResolver.Credentials zoom = zoomRes.resolveForDb(dbName);
        if (zoom.isComplete()) {
            zoomClient.send(zoom.accountId(), zoom.clientId(), zoom.clientSecret(),
                    zoom.fromNumber(), toNumber, body);
            return;
        }

        TwilioCredentialsResolver.Credentials twilio = twilioRes.resolveForDb(dbName);
        if (twilio.isComplete()) {
            try {
                twilioClient.send(twilio.accountSid(), twilio.authToken(),
                        twilio.fromNumber(), toNumber, body);
            } catch (OdCalendarSmsClient.TwilioException e) {
                throw new IOException(e.getMessage(), e);
            }
            return;
        }

        throw new SmsNotConfiguredException();
    }
}
