-- SMS settings (templates only). Twilio credentials (account_sid, auth_token,
-- from_number) live in `tenant_credentials` (kernel V1) as of Phase 2 of
-- credential centralization — see TwilioCredentialsResolver and the
-- /api/{admin,tenant}/credentials/twilio endpoints.
CREATE TABLE opendental_calendar_sms_config (
    id                  INT PRIMARY KEY DEFAULT 1,
    template_confirm    TEXT NOT NULL,
    template_reminder   TEXT NOT NULL,
    template_review     TEXT NOT NULL
);

INSERT INTO opendental_calendar_sms_config (id, template_confirm, template_reminder, template_review) VALUES
(1,
 'Hi {name}, your appointment at {clinic} on {date} at {time} is confirmed. Reply STOP to opt out.',
 'Hi {name}, reminder: you have an appointment at {clinic} on {date} at {time}. Reply STOP to opt out.',
 'Hi {name}, thank you for visiting {clinic}! We hope your visit went well and look forward to seeing you again. Reply STOP to opt out.');
