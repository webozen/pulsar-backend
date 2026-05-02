package com.pulsar.opendentalcalendar;

import com.pulsar.kernel.security.RequireModule;
import com.pulsar.kernel.tenant.TenantContext;
import com.pulsar.kernel.tenant.TenantDataSources;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/opendental-calendar")
@RequireModule("opendental-calendar")
public class OpendentalCalendarController {

    private final TenantDataSources tenantDs;
    private final OdCalendarQueryClient client;
    private final OdCalendarSmsClient smsClient;

    public OpendentalCalendarController(TenantDataSources tenantDs, OdCalendarQueryClient client,
            OdCalendarSmsClient smsClient) {
        this.tenantDs = tenantDs;
        this.client = client;
        this.smsClient = smsClient;
    }

    public record ConfigRequest(
        @NotBlank String odDeveloperKey,
        @NotBlank String odCustomerKey
    ) {}

    @GetMapping("/config")
    public Map<String, Object> configStatus() {
        var t = TenantContext.require();
        JdbcTemplate jdbc = new JdbcTemplate(tenantDs.forDb(t.dbName()));
        for (String query : new String[]{
            "SELECT 1 FROM opendental_calendar_config WHERE id = 1",
            "SELECT 1 FROM opendental_ai_config WHERE id = 1",
        }) {
            try {
                if (!jdbc.queryForList(query).isEmpty()) return Map.of("onboarded", true);
            } catch (Exception ignored) {}
        }
        return Map.of("onboarded", false);
    }

    @PostMapping("/config")
    public Map<String, Object> saveConfig(@Valid @RequestBody ConfigRequest req) {
        var t = TenantContext.require();
        JdbcTemplate jdbc = new JdbcTemplate(tenantDs.forDb(t.dbName()));
        jdbc.update(
            "INSERT INTO opendental_calendar_config (id, od_developer_key, od_customer_key) " +
            "VALUES (1, ?, ?) " +
            "ON DUPLICATE KEY UPDATE od_developer_key = VALUES(od_developer_key), " +
            "od_customer_key = VALUES(od_customer_key)",
            req.odDeveloperKey(), req.odCustomerKey()
        );
        return Map.of("onboarded", true);
    }

    @GetMapping("/operatories")
    public List<Map<String, Object>> operatories() {
        var keys = loadKeys();
        try {
            return client.query(keys.devKey(), keys.custKey(),
                "SELECT OperatoryNum, OpName, Abbrev FROM operatory " +
                "WHERE IsHidden = 0 ORDER BY ItemOrder");
        } catch (IOException | OdCalendarQueryClient.OdException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage());
        }
    }

    @GetMapping("/appointments")
    public List<Map<String, Object>> appointments(@RequestParam String date) {
        if (!date.matches("\\d{4}-\\d{2}-\\d{2}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "date must be YYYY-MM-DD");
        }
        try {
            java.time.LocalDate.parse(date);
        } catch (java.time.format.DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid date: " + date);
        }
        // Safe to concatenate: value is validated to YYYY-MM-DD above (only digits + hyphens,
        // logically valid date) — no SQL metacharacter can appear in this pattern.
        var keys = loadKeys();
        String sql =
            "SELECT a.AptNum, a.PatNum, a.AptDateTime, " +
            "       DATE_ADD(a.AptDateTime, INTERVAL LENGTH(a.Pattern)*5 MINUTE) AS AptTimeEnd, " +
            "       a.Op, a.ProcDescript, a.AptStatus, a.IsNewPatient, " +
            "       p.FName, p.LName, p.HmPhone, p.WirelessPhone, p.Birthdate, " +
            "       o.OpName, o.Abbrev AS OpAbbrev, " +
            "       prov.Abbr AS ProvAbbr " +
            "FROM appointment a " +
            "JOIN patient p ON a.PatNum = p.PatNum " +
            "JOIN operatory o ON a.Op = o.OperatoryNum " +
            "JOIN provider prov ON a.ProvNum = prov.ProvNum " +
            "WHERE DATE(a.AptDateTime) = '" + date + "' " +
            "  AND a.AptStatus IN (1, 2, 5) " + // 1=Scheduled, 2=Complete, 5=Broken
            "ORDER BY a.AptDateTime";
        try {
            return client.query(keys.devKey(), keys.custKey(), sql);
        } catch (IOException | OdCalendarQueryClient.OdException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage());
        }
    }

    @GetMapping("/patients/{patNum}/treatment-plan")
    public List<Map<String, Object>> treatmentPlan(@PathVariable long patNum) {
        var keys = loadKeys();
        String sql =
            "SELECT pl.ProcNum, pl.ProcDate, pl.ProcFee, pl.ProcStatus, " +
            "       pl.ToothNum, pl.UnitQty, pc.ProcCode, pc.Descript " +
            "FROM procedurelog pl " +
            "JOIN procedurecode pc ON pl.CodeNum = pc.CodeNum " +
            "WHERE pl.PatNum = " + patNum + " AND pl.ProcStatus = 1 " +
            "ORDER BY pl.ProcDate DESC";
        try {
            return client.query(keys.devKey(), keys.custKey(), sql);
        } catch (IOException | OdCalendarQueryClient.OdException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage());
        }
    }

    @GetMapping("/patients/{patNum}/ledger")
    public List<Map<String, Object>> ledger(@PathVariable long patNum) {
        var keys = loadKeys();
        String sql =
            "SELECT p.PayNum, p.PayDate, p.PayAmt, p.PayNote, " +
            "       COALESCE(d.ItemName, 'Other') AS PayTypeName " +
            "FROM payment p " +
            "LEFT JOIN definition d ON p.PayType = d.DefNum " +
            "WHERE p.PatNum = " + patNum + " " +
            "ORDER BY p.PayDate DESC " +
            "LIMIT 100";
        try {
            return client.query(keys.devKey(), keys.custKey(), sql);
        } catch (IOException | OdCalendarQueryClient.OdException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage());
        }
    }

    @GetMapping("/patients/{patNum}/commlogs")
    public List<Map<String, Object>> commLogs(@PathVariable long patNum) {
        var keys = loadKeys();
        String sql =
            "SELECT CommlogNum, CommDateTime, Note, Mode_, SentOrReceived " +
            "FROM commlog " +
            "WHERE PatNum = " + patNum + " " +
            "ORDER BY CommDateTime DESC " +
            "LIMIT 100";
        try {
            return client.query(keys.devKey(), keys.custKey(), sql);
        } catch (IOException | OdCalendarQueryClient.OdException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage());
        }
    }

    @GetMapping("/patients/{patNum}/apt-history")
    public List<Map<String, Object>> aptHistory(@PathVariable long patNum) {
        var keys = loadKeys();
        String sql =
            "SELECT a.AptNum, a.AptDateTime, " +
            "       DATE_ADD(a.AptDateTime, INTERVAL LENGTH(a.Pattern)*5 MINUTE) AS AptTimeEnd, " +
            "       a.AptStatus, a.ProcDescript, " +
            "       prov.Abbr AS ProvAbbr, o.OpName " +
            "FROM appointment a " +
            "JOIN provider prov ON a.ProvNum = prov.ProvNum " +
            "JOIN operatory o ON a.Op = o.OperatoryNum " +
            "WHERE a.PatNum = " + patNum + " " +
            "ORDER BY a.AptDateTime DESC " +
            "LIMIT 100";
        try {
            return client.query(keys.devKey(), keys.custKey(), sql);
        } catch (IOException | OdCalendarQueryClient.OdException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage());
        }
    }

    @GetMapping("/patients/{patNum}/claims")
    public List<Map<String, Object>> claims(@PathVariable long patNum) {
        var keys = loadKeys();
        String sql =
            "SELECT c.ClaimNum, c.DateService, c.DateSent, c.ClaimStatus, c.ClaimType, " +
            "       c.ClaimFee, c.InsPayEst, c.InsPayAmt, c.DedApplied, c.WriteOff, " +
            "       c.PreAuthString, c.ReasonUnderPaid, carr.CarrierName " +
            "FROM claim c " +
            "JOIN insplan ip ON c.PlanNum = ip.PlanNum " +
            "JOIN carrier carr ON ip.CarrierNum = carr.CarrierNum " +
            "WHERE c.PatNum = " + patNum + " " +
            "ORDER BY c.DateService DESC " +
            "LIMIT 100";
        try {
            return client.query(keys.devKey(), keys.custKey(), sql);
        } catch (IOException | OdCalendarQueryClient.OdException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage());
        }
    }

    // ── SMS Config ────────────────────────────────────────────────────────────

    public record SmsConfigRequest(
        @NotBlank String accountSid,
        String authToken,   // optional on update — blank means keep existing token
        @NotBlank String fromNumber,
        @NotBlank String templateConfirm,
        @NotBlank String templateReminder,
        @NotBlank String templateReview,
        String clinicName,
        String clinicAddress
    ) {}

    public record SmsPreviewRequest(@NotBlank String type, @NotBlank String aptDateTime) {}

    public record SmsSendRequest(@NotBlank String body) {}

    @GetMapping("/sms-config")
    public Map<String, Object> getSmsConfig() {
        var t = TenantContext.require();
        JdbcTemplate jdbc = new JdbcTemplate(tenantDs.forDb(t.dbName()));
        try {
            var rows = jdbc.queryForList(
                "SELECT account_sid, auth_token, from_number, template_confirm, " +
                "template_reminder, template_review, clinic_name, clinic_address " +
                "FROM opendental_calendar_sms_config WHERE id = 1");
            if (!rows.isEmpty()) {
                var row = rows.get(0);
                String accountSid = (String) row.get("account_sid");
                boolean enabled = accountSid != null && !accountSid.isBlank();
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("enabled", enabled);
                result.put("accountSid", accountSid != null ? accountSid : "");
                result.put("authToken", "••••");
                result.put("fromNumber", row.get("from_number") != null ? row.get("from_number") : "");
                result.put("templateConfirm", row.get("template_confirm") != null ? row.get("template_confirm") : "");
                result.put("templateReminder", row.get("template_reminder") != null ? row.get("template_reminder") : "");
                result.put("templateReview", row.get("template_review") != null ? row.get("template_review") : "");
                result.put("clinicName", row.get("clinic_name") != null ? row.get("clinic_name") : "");
                result.put("clinicAddress", row.get("clinic_address") != null ? row.get("clinic_address") : "");
                return result;
            }
        } catch (Exception ignored) {}
        Map<String, Object> empty = new LinkedHashMap<>();
        empty.put("enabled", false);
        empty.put("accountSid", "");
        empty.put("authToken", "");
        empty.put("fromNumber", "");
        empty.put("templateConfirm", "");
        empty.put("templateReminder", "");
        empty.put("templateReview", "");
        empty.put("clinicName", "");
        empty.put("clinicAddress", "");
        return empty;
    }

    @PostMapping("/sms-config")
    public Map<String, Object> saveSmsConfig(@Valid @RequestBody SmsConfigRequest req) {
        var t = TenantContext.require();
        JdbcTemplate jdbc = new JdbcTemplate(tenantDs.forDb(t.dbName()));
        boolean hasNewToken = req.authToken() != null && !req.authToken().isBlank();
        String authTokenUpdate = hasNewToken ? "auth_token = VALUES(auth_token), " : "";
        jdbc.update(
            "INSERT INTO opendental_calendar_sms_config " +
            "(id, account_sid, auth_token, from_number, template_confirm, template_reminder, template_review, clinic_name, clinic_address) " +
            "VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?) " +
            "ON DUPLICATE KEY UPDATE " +
            "account_sid = VALUES(account_sid), " + authTokenUpdate +
            "from_number = VALUES(from_number), template_confirm = VALUES(template_confirm), " +
            "template_reminder = VALUES(template_reminder), template_review = VALUES(template_review), " +
            "clinic_name = VALUES(clinic_name), clinic_address = VALUES(clinic_address)",
            req.accountSid(), hasNewToken ? req.authToken() : "", req.fromNumber(),
            req.templateConfirm(), req.templateReminder(), req.templateReview(),
            req.clinicName() != null ? req.clinicName() : "",
            req.clinicAddress() != null ? req.clinicAddress() : ""
        );
        return Map.of("saved", true);
    }

    @PostMapping("/patients/{patNum}/preview-sms")
    public Map<String, Object> previewSms(@PathVariable long patNum,
            @Valid @RequestBody SmsPreviewRequest req) {
        var smsRow = loadSmsConfig();
        String accountSid = (String) smsRow.get("account_sid");
        if (accountSid == null || accountSid.isBlank()) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "SMS not configured");
        }

        var keys = loadKeys();
        List<Map<String, Object>> patRows;
        try {
            patRows = client.query(keys.devKey(), keys.custKey(),
                "SELECT FName, TxtMsgOk, WirelessPhone, HmPhone FROM patient WHERE PatNum = " + patNum);
        } catch (IOException | OdCalendarQueryClient.OdException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage());
        }
        if (patRows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Patient not found");
        }
        var pat = patRows.get(0);

        Object txtMsgOk = pat.get("TxtMsgOk");
        // TxtMsgOk: 0=Unknown, 1=Yes, 2=No (explicitly opted out). Block only value "2".
        if (txtMsgOk != null && "2".equals(String.valueOf(txtMsgOk))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Patient has opted out of text messages");
        }

        String wirelessPhone = (String) pat.get("WirelessPhone");
        String hmPhone = (String) pat.get("HmPhone");
        String rawPhone = (wirelessPhone != null && !wirelessPhone.isBlank()) ? wirelessPhone : hmPhone;
        String toNumber;
        if (rawPhone == null || rawPhone.isBlank()) {
            toNumber = "+15198002773"; // trial fallback — no phone on file
        } else {
            String digits = rawPhone.replaceAll("[^0-9]", "");
            if (digits.length() == 10) toNumber = "+1" + digits;
            else if (digits.length() == 11 && digits.startsWith("1")) toNumber = "+" + digits;
            else toNumber = rawPhone;
        }
        // TRIAL MODE: always redirect to trial number regardless of patient phone
        toNumber = "+15198002773";

        String template;
        String type = req.type();
        if ("confirm".equals(type)) {
            template = (String) smsRow.get("template_confirm");
        } else if ("remind".equals(type)) {
            template = (String) smsRow.get("template_reminder");
        } else if ("review".equals(type)) {
            template = (String) smsRow.get("template_review");
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "type must be confirm, remind, or review");
        }
        if (template == null) template = "";

        DateTimeFormatter inputFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        LocalDateTime aptDt;
        try {
            aptDt = LocalDateTime.parse(req.aptDateTime(), inputFmt);
        } catch (java.time.format.DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid aptDateTime format: expected yyyy-MM-dd HH:mm:ss");
        }
        String datePart = aptDt.format(DateTimeFormatter.ofPattern("EEEE, MMMM d", java.util.Locale.US));
        String timePart = aptDt.format(DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.US));

        String fName = pat.get("FName") != null ? (String) pat.get("FName") : "";

        // Look up clinic from OD via the appointment's ClinicNum
        String clinicName = "";
        String clinicAddress = "";
        String clinicPhone = "";
        try {
            String aptDate = req.aptDateTime().substring(0, 10);
            String aptTime = req.aptDateTime().substring(11);
            var clinicRows = client.query(keys.devKey(), keys.custKey(),
                "SELECT c.Description, c.Address, c.City, c.State, c.Zip, c.Phone " +
                "FROM appointment a " +
                "LEFT JOIN clinic c ON a.ClinicNum = c.ClinicNum " +
                "WHERE a.PatNum = " + patNum + " " +
                "AND DATE(a.AptDateTime) = '" + aptDate + "' " +
                "AND TIME(a.AptDateTime) = '" + aptTime + "' " +
                "LIMIT 1");
            if (!clinicRows.isEmpty()) {
                var c = clinicRows.get(0);
                clinicName    = c.get("Description") != null ? String.valueOf(c.get("Description")) : "";
                String addr   = c.get("Address")     != null ? String.valueOf(c.get("Address"))     : "";
                String city   = c.get("City")        != null ? String.valueOf(c.get("City"))        : "";
                String state  = c.get("State")       != null ? String.valueOf(c.get("State"))       : "";
                String zip    = c.get("Zip")         != null ? String.valueOf(c.get("Zip"))         : "";
                clinicPhone   = c.get("Phone")       != null ? String.valueOf(c.get("Phone"))       : "";
                clinicAddress = java.util.stream.Stream.of(addr, city, state, zip)
                    .filter(s -> s != null && !s.isBlank()).collect(java.util.stream.Collectors.joining(", "));
            }
        } catch (Exception ignored) {}
        // Fall back to SMS config values if OD clinic lookup returned nothing
        if (clinicName.isBlank())    clinicName    = smsRow.get("clinic_name")    != null ? (String) smsRow.get("clinic_name")    : "";
        if (clinicAddress.isBlank()) clinicAddress = smsRow.get("clinic_address") != null ? (String) smsRow.get("clinic_address") : "";

        String preview = template
            .replace("{patientName}", fName)
            .replace("{clinicName}", clinicName.isBlank() ? "our clinic" : clinicName)
            .replace("{clinicAddress}", clinicAddress)
            .replace("{clinicPhone}", clinicPhone)
            .replace("{appointmentDate}", datePart)
            .replace("{appointmentTime}", timePart)
            // short aliases kept for backwards-compat with existing templates
            .replace("{name}", fName)
            .replace("{clinic}", clinicName.isBlank() ? "our clinic" : clinicName)
            .replace("{address}", clinicAddress)
            .replace("{phone}", clinicPhone)
            .replace("{date}", datePart)
            .replace("{time}", timePart);

        return Map.of("to", toNumber, "preview", preview);
    }

    @PostMapping("/patients/{patNum}/send-sms")
    public Map<String, Object> sendSms(@PathVariable long patNum,
            @Valid @RequestBody SmsSendRequest req) {
        var smsRow = loadSmsConfig();
        String accountSid = (String) smsRow.get("account_sid");
        if (accountSid == null || accountSid.isBlank()) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "SMS not configured");
        }
        String authToken = (String) smsRow.get("auth_token");
        String fromNumber = (String) smsRow.get("from_number");

        // Re-derive phone number from patient record — never trust caller-supplied destination
        var keys = loadKeys();
        List<Map<String, Object>> patRows;
        try {
            patRows = client.query(keys.devKey(), keys.custKey(),
                "SELECT TxtMsgOk, WirelessPhone, HmPhone FROM patient WHERE PatNum = " + patNum);
        } catch (IOException | OdCalendarQueryClient.OdException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage());
        }
        if (patRows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Patient not found");
        }
        var pat = patRows.get(0);

        Object txtMsgOk = pat.get("TxtMsgOk");
        // TxtMsgOk: 0=Unknown, 1=Yes, 2=No (explicitly opted out). Block only value "2".
        if (txtMsgOk != null && "2".equals(String.valueOf(txtMsgOk))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Patient has opted out of text messages");
        }

        String wirelessPhone = (String) pat.get("WirelessPhone");
        String hmPhone = (String) pat.get("HmPhone");
        String rawPhone = (wirelessPhone != null && !wirelessPhone.isBlank()) ? wirelessPhone : hmPhone;
        if (rawPhone == null || rawPhone.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No phone number on file");
        }

        String digits = rawPhone.replaceAll("[^0-9]", "");
        String toNumber;
        if (digits.length() == 10) {
            toNumber = "+1" + digits;
        } else if (digits.length() == 11 && digits.startsWith("1")) {
            toNumber = "+" + digits;
        } else {
            toNumber = rawPhone;
        }
        // TRIAL MODE: always redirect to trial number regardless of patient phone
        toNumber = "+15198002773";

        try {
            smsClient.send(accountSid, authToken, fromNumber, toNumber, req.body());
        } catch (OdCalendarSmsClient.TwilioException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage());
        }
        return Map.of("sent", true, "to", toNumber);
    }

    private Map<String, Object> loadSmsConfig() {
        var t = TenantContext.require();
        JdbcTemplate jdbc = new JdbcTemplate(tenantDs.forDb(t.dbName()));
        try {
            var rows = jdbc.queryForList(
                "SELECT account_sid, auth_token, from_number, template_confirm, " +
                "template_reminder, template_review, clinic_name, clinic_address " +
                "FROM opendental_calendar_sms_config WHERE id = 1");
            if (!rows.isEmpty()) return rows.get(0);
        } catch (Exception ignored) {}
        return new HashMap<>();
    }

    // ── OD Keys ───────────────────────────────────────────────────────────────

    private record Keys(String devKey, String custKey) {}

    private Keys loadKeys() {
        var t = TenantContext.require();
        JdbcTemplate jdbc = new JdbcTemplate(tenantDs.forDb(t.dbName()));
        // Own config takes priority; fall back to opendental_ai_config so tenants
        // that already set up OpenDental AI don't have to enter the same keys again.
        for (String query : new String[]{
            "SELECT od_developer_key, od_customer_key FROM opendental_calendar_config WHERE id = 1",
            "SELECT od_developer_key, od_customer_key FROM opendental_ai_config WHERE id = 1",
        }) {
            try {
                var rows = jdbc.queryForList(query);
                if (!rows.isEmpty()) {
                    var row = rows.get(0);
                    return new Keys((String) row.get("od_developer_key"), (String) row.get("od_customer_key"));
                }
            } catch (Exception ignored) {
                // table may not exist if the other module was never activated
            }
        }
        throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "Module not configured");
    }
}
