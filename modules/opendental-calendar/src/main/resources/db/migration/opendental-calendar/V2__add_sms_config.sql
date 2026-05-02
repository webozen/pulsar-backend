CREATE TABLE opendental_calendar_sms_config (
    id                  INT PRIMARY KEY DEFAULT 1,
    account_sid         VARCHAR(64)  NOT NULL DEFAULT '',
    auth_token          VARCHAR(64)  NOT NULL DEFAULT '',
    from_number         VARCHAR(20)  NOT NULL DEFAULT '',
    template_confirm    TEXT         NOT NULL,
    template_reminder   TEXT         NOT NULL,
    template_review     TEXT         NOT NULL
);

INSERT INTO opendental_calendar_sms_config (id, account_sid, auth_token, from_number, template_confirm, template_reminder, template_review) VALUES
(1, '', '', '', 'Hi {name}, your appointment at {clinic} on {date} at {time} is confirmed. Reply STOP to opt out.', 'Hi {name}, reminder: you have an appointment at {clinic} on {date} at {time}. Reply STOP to opt out.', 'Hi {name}, thank you for visiting {clinic}! We hope your visit went well and look forward to seeing you again. Reply STOP to opt out.');
