package com.pulsar.opendentalcalendar;

import com.pulsar.kernel.auth.Principal;
import com.pulsar.kernel.auth.PrincipalContext;
import com.pulsar.kernel.credentials.OpenDentalKeyResolver;
import com.pulsar.kernel.security.RequireModule;
import com.pulsar.kernel.tenant.TenantContext;
import com.pulsar.kernel.tenant.TenantDataSources;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/opendental-calendar")
@RequireModule("opendental-calendar")
public class OpendentalCalendarController {

    private final TenantDataSources tenantDs;
    private final OdCalendarQueryClient client;
    private final OpenDentalKeyResolver opendentalKeyResolver;
    private final SmsDispatcher smsDispatcher;

    public OpendentalCalendarController(
        TenantDataSources tenantDs,
        OdCalendarQueryClient client,
        OpenDentalKeyResolver opendentalKeyResolver,
        SmsDispatcher smsDispatcher
    ) {
        this.tenantDs = tenantDs;
        this.client = client;
        this.opendentalKeyResolver = opendentalKeyResolver;
        this.smsDispatcher = smsDispatcher;
    }

    @GetMapping("/config")
    public Map<String, Object> configStatus() {
        var t = TenantContext.require();
        OpenDentalKeyResolver.Status s = opendentalKeyResolver.statusForDb(t.dbName());
        return Map.of("onboarded", s.isComplete());
    }

    @GetMapping("/operatories")
    public List<Map<String, Object>> operatories() {
        OpenDentalKeyResolver.Keys keys = loadOdKeys();
        try {
            return client.query(keys.developerKey(), keys.customerKey(),
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
        OpenDentalKeyResolver.Keys keys = loadOdKeys();
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
            return client.query(keys.developerKey(), keys.customerKey(), sql);
        } catch (IOException | OdCalendarQueryClient.OdException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage());
        }
    }

    @GetMapping("/patients/{patNum}/treatment-plan")
    public List<Map<String, Object>> treatmentPlan(@PathVariable long patNum) {
        OpenDentalKeyResolver.Keys keys = loadOdKeys();
        String sql =
            "SELECT pl.ProcNum, pl.ProcDate, pl.ProcFee, pl.ProcStatus, " +
            "       pl.ToothNum, pl.UnitQty, pc.ProcCode, pc.Descript " +
            "FROM procedurelog pl " +
            "JOIN procedurecode pc ON pl.CodeNum = pc.CodeNum " +
            "WHERE pl.PatNum = " + patNum + " AND pl.ProcStatus = 1 " +
            "ORDER BY pl.ProcDate DESC";
        return runQuery(keys, sql);
    }

    @GetMapping("/patients/{patNum}/ledger")
    public List<Map<String, Object>> ledger(@PathVariable long patNum) {
        OpenDentalKeyResolver.Keys keys = loadOdKeys();
        String sql =
            "SELECT p.PayNum, p.PayDate, p.PayAmt, p.PayNote, " +
            "       COALESCE(d.ItemName, 'Other') AS PayTypeName " +
            "FROM payment p " +
            "LEFT JOIN definition d ON p.PayType = d.DefNum " +
            "WHERE p.PatNum = " + patNum + " " +
            "ORDER BY p.PayDate DESC " +
            "LIMIT 100";
        return runQuery(keys, sql);
    }

    @GetMapping("/patients/{patNum}/commlogs")
    public List<Map<String, Object>> commLogs(@PathVariable long patNum) {
        OpenDentalKeyResolver.Keys keys = loadOdKeys();
        String sql =
            "SELECT CommlogNum, CommDateTime, Note, Mode_, SentOrReceived " +
            "FROM commlog " +
            "WHERE PatNum = " + patNum + " " +
            "ORDER BY CommDateTime DESC " +
            "LIMIT 100";
        return runQuery(keys, sql);
    }

    @GetMapping("/patients/{patNum}/apt-history")
    public List<Map<String, Object>> aptHistory(@PathVariable long patNum) {
        OpenDentalKeyResolver.Keys keys = loadOdKeys();
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
        return runQuery(keys, sql);
    }

    @GetMapping("/patients/{patNum}/claims")
    public List<Map<String, Object>> claims(@PathVariable long patNum) {
        OpenDentalKeyResolver.Keys keys = loadOdKeys();
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
        return runQuery(keys, sql);
    }

    private List<Map<String, Object>> runQuery(OpenDentalKeyResolver.Keys keys, String sql) {
        try {
            return client.query(keys.developerKey(), keys.customerKey(), sql);
        } catch (IOException | OdCalendarQueryClient.OdException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage());
        }
    }

    // ── SMS settings (templates + clinic info) — credentials moved to tenant_credentials ─

    /**
     * Settings (templates, clinic name/address) only — Twilio credentials live
     * in {@code tenant_credentials} as of Phase 2 and are managed via the admin
     * tenant detail page or the tenant Settings page.
     */
    public record SmsSettingsRequest(
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
        String activeProvider = smsDispatcher.activeProvider(t.dbName());
        Map<String, Object> settings = loadSmsSettings();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", !activeProvider.equals("none"));
        result.put("smsProvider", activeProvider);
        // Settings stay surfaced for the templates form; credential fields are no
        // longer returned here (they live in /api/{admin,tenant}/credentials/twilio).
        result.put("templateConfirm",  settings.getOrDefault("template_confirm", ""));
        result.put("templateReminder", settings.getOrDefault("template_reminder", ""));
        result.put("templateReview",   settings.getOrDefault("template_review", ""));
        result.put("clinicName",       settings.getOrDefault("clinic_name", ""));
        result.put("clinicAddress",    settings.getOrDefault("clinic_address", ""));
        return result;
    }

    @PostMapping("/sms-config")
    public Map<String, Object> saveSmsSettings(@Valid @RequestBody SmsSettingsRequest req) {
        var t = TenantContext.require();
        JdbcTemplate jdbc = new JdbcTemplate(tenantDs.forDb(t.dbName()));
        jdbc.update(
            "INSERT INTO opendental_calendar_sms_config " +
            "(id, template_confirm, template_reminder, template_review, clinic_name, clinic_address) " +
            "VALUES (1, ?, ?, ?, ?, ?) " +
            "ON DUPLICATE KEY UPDATE " +
            "template_confirm = VALUES(template_confirm), " +
            "template_reminder = VALUES(template_reminder), " +
            "template_review = VALUES(template_review), " +
            "clinic_name = VALUES(clinic_name), " +
            "clinic_address = VALUES(clinic_address)",
            req.templateConfirm(), req.templateReminder(), req.templateReview(),
            req.clinicName() != null ? req.clinicName() : "",
            req.clinicAddress() != null ? req.clinicAddress() : ""
        );
        return Map.of("saved", true);
    }

    @PostMapping("/patients/{patNum}/preview-sms")
    public Map<String, Object> previewSms(@PathVariable long patNum,
            @Valid @RequestBody SmsPreviewRequest req) {
        String dbName = TenantContext.require().dbName();
        if (smsDispatcher.activeProvider(dbName).equals("none")) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "sms_not_configured");
        }
        String fromNumber = smsDispatcher.fromNumber(dbName);
        Map<String, Object> settings = loadSmsSettings();
        OpenDentalKeyResolver.Keys keys = loadOdKeys();

        List<Map<String, Object>> patRows;
        try {
            patRows = client.query(keys.developerKey(), keys.customerKey(),
                "SELECT FName, TxtMsgOk, WirelessPhone, HmPhone FROM patient WHERE PatNum = " + patNum);
        } catch (IOException | OdCalendarQueryClient.OdException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage());
        }
        if (patRows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Patient not found");
        }
        var pat = patRows.get(0);

        Object txtMsgOk = pat.get("TxtMsgOk");
        if (txtMsgOk != null && "2".equals(String.valueOf(txtMsgOk))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Patient has opted out of text messages");
        }

        // TRIAL MODE: redirect to provider-specific trial number.
        String toNumber = "zoom-phone".equals(smsDispatcher.activeProvider(dbName))
            ? "+14014510630" : "+15198002773";

        String template;
        String type = req.type();
        if ("confirm".equals(type)) {
            template = (String) settings.get("template_confirm");
        } else if ("remind".equals(type)) {
            template = (String) settings.get("template_reminder");
        } else if ("review".equals(type)) {
            template = (String) settings.get("template_review");
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
        String clinicName = "";
        String clinicAddress = "";
        String clinicPhone = "";
        try {
            String aptDate = req.aptDateTime().substring(0, 10);
            String aptTime = req.aptDateTime().substring(11);
            var clinicRows = client.query(keys.developerKey(), keys.customerKey(),
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
        // Fall back to settings values if OD clinic lookup returned nothing
        if (clinicName.isBlank())    clinicName    = settings.get("clinic_name")    != null ? (String) settings.get("clinic_name")    : "";
        if (clinicAddress.isBlank()) clinicAddress = settings.get("clinic_address") != null ? (String) settings.get("clinic_address") : "";

        String preview = template
            .replace("{patientName}", fName)
            .replace("{clinicName}", clinicName.isBlank() ? "our clinic" : clinicName)
            .replace("{clinicAddress}", clinicAddress)
            .replace("{clinicPhone}", clinicPhone)
            .replace("{appointmentDate}", datePart)
            .replace("{appointmentTime}", timePart)
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
        OpenDentalKeyResolver.Keys keys = loadOdKeys();

        List<Map<String, Object>> patRows;
        try {
            patRows = client.query(keys.developerKey(), keys.customerKey(),
                "SELECT TxtMsgOk, WirelessPhone, HmPhone FROM patient WHERE PatNum = " + patNum);
        } catch (IOException | OdCalendarQueryClient.OdException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage());
        }
        if (patRows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Patient not found");
        }
        var pat = patRows.get(0);
        Object txtMsgOk = pat.get("TxtMsgOk");
        if (txtMsgOk != null && "2".equals(String.valueOf(txtMsgOk))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Patient has opted out of text messages");
        }

        String wirelessPhone = (String) pat.get("WirelessPhone");
        String hmPhone = (String) pat.get("HmPhone");
        String rawPhone = (wirelessPhone != null && !wirelessPhone.isBlank()) ? wirelessPhone : hmPhone;
        if (rawPhone == null || rawPhone.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No phone number on file");
        }
        String dbName = TenantContext.require().dbName();
        // TRIAL MODE: redirect to provider-specific trial number.
        String toNumber = "zoom-phone".equals(smsDispatcher.activeProvider(dbName))
            ? "+14014510630" : "+15198002773";
        try {
            smsDispatcher.send(dbName, toNumber, req.body());
        } catch (SmsDispatcher.SmsNotConfiguredException e) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "sms_not_configured");
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "sms_send_failed: " + e.getMessage());
        }
        return Map.of("sent", true, "to", toNumber);
    }

    private Map<String, Object> loadSmsSettings() {
        var t = TenantContext.require();
        JdbcTemplate jdbc = new JdbcTemplate(tenantDs.forDb(t.dbName()));
        try {
            var rows = jdbc.queryForList(
                "SELECT template_confirm, template_reminder, template_review, " +
                "clinic_name, clinic_address " +
                "FROM opendental_calendar_sms_config WHERE id = 1");
            if (!rows.isEmpty()) return rows.get(0);
        } catch (Exception ignored) {}
        return new LinkedHashMap<>();
    }

    /** Suppress unused-import warning until OD-config-status hook is rewired. */
    @SuppressWarnings("unused")
    private void _principalContextStub() { var p = PrincipalContext.get(); if (p instanceof Principal.TenantUser) {} }

    private OpenDentalKeyResolver.Keys loadOdKeys() {
        var t = TenantContext.require();
        OpenDentalKeyResolver.Keys keys = opendentalKeyResolver.resolveForDb(t.dbName());
        if (!keys.isComplete()) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "OpenDental keys not configured");
        }
        return keys;
    }
}
