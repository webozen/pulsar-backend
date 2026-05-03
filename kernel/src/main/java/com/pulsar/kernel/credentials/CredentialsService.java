package com.pulsar.kernel.credentials;

import com.pulsar.kernel.tenant.TenantDataSources;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Centralized per-tenant credential store. Replaces the scattered
 * {@code *_config.<key_name>} columns across modules with a single
 * encrypted {@code tenant_credentials} table.
 *
 * <p>Encryption: AES-256-GCM with a 96-bit random IV per row. Master key
 * comes from {@code PULSAR_CREDENTIALS_MASTER_KEY} (base64-encoded 32
 * bytes). When the env var is absent (dev only), the service falls back
 * to plaintext storage with a one-time WARN — same pattern as the JWT
 * dev sentinel. {@link #encryptionEnabled()} surfaces this for ops/UI.
 *
 * <p>Resolution priority:
 * <ol>
 *   <li>If {@code use_platform_default = true} and a platform default
 *       env var is set for the provider → return that.</li>
 *   <li>If a row's ciphertext decrypts → return decrypted value.</li>
 *   <li>If {@code legacyFallback} returns a value → return that
 *       (used during the old-column → new-table transition).</li>
 *   <li>Otherwise null.</li>
 * </ol>
 */
@Service
public class CredentialsService {
    private static final Logger log = LoggerFactory.getLogger(CredentialsService.class);
    private static final SecureRandom RNG = new SecureRandom();
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    public record Status(boolean hasTenantValue, boolean useDefault, boolean defaultAvailable) {}

    public record Resolution(String value, Source source) {
        public boolean isPresent() { return value != null && !value.isBlank(); }
    }

    public enum Source { TENANT, PLATFORM_DEFAULT, NONE }

    private final TenantDataSources tenantDs;
    private final byte[] masterKey;
    private final boolean encryptionEnabled;
    private final Map<String, String> platformDefaults;

    public CredentialsService(
        TenantDataSources tenantDs,
        @Value("${pulsar.credentials.master-key:}") String masterKeyB64,
        @Value("${pulsar.translate.default-gemini-key:}") String defaultGeminiKey,
        Environment environment
    ) {
        this.tenantDs = tenantDs;
        boolean prodLike = isProdLikeProfile(environment);
        if (masterKeyB64 == null || masterKeyB64.isBlank()) {
            if (prodLike) {
                throw new IllegalStateException(
                    "PULSAR_CREDENTIALS_MASTER_KEY must be set in prod-like profiles; "
                        + "refusing to start. Generate a 32-byte random key and base64-encode it.");
            }
            this.masterKey = null;
            this.encryptionEnabled = false;
            log.warn("PULSAR_CREDENTIALS_MASTER_KEY is not set — tenant_credentials values will be stored "
                + "in plaintext. OK for local dev; refused in prod-like profiles.");
        } else {
            byte[] decoded;
            try {
                decoded = Base64.getDecoder().decode(masterKeyB64.trim());
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("PULSAR_CREDENTIALS_MASTER_KEY is not valid base64", e);
            }
            if (decoded.length != 32) {
                throw new IllegalStateException(
                    "PULSAR_CREDENTIALS_MASTER_KEY must decode to exactly 32 bytes; got " + decoded.length);
            }
            this.masterKey = decoded;
            this.encryptionEnabled = true;
        }
        this.platformDefaults = new LinkedHashMap<>();
        if (defaultGeminiKey != null && !defaultGeminiKey.isBlank()) {
            this.platformDefaults.put("gemini", defaultGeminiKey);
        }
    }

    /**
     * Mirrors {@code JwtService.validateSecret}'s notion of prod-like: explicit
     * {@code prod} profile, or any non-dev/local/test active profile. No active
     * profile at all is treated as dev-like to preserve local DX.
     */
    private static boolean isProdLikeProfile(Environment environment) {
        String[] active = environment.getActiveProfiles();
        boolean hasProd = Arrays.stream(active).anyMatch(p -> p.equalsIgnoreCase("prod"));
        boolean hasDevLike = Arrays.stream(active).anyMatch(p -> {
            String lower = p.toLowerCase();
            return lower.startsWith("dev") || lower.startsWith("local") || lower.startsWith("test");
        });
        return hasProd || (active.length > 0 && !hasDevLike);
    }

    public boolean encryptionEnabled() { return encryptionEnabled; }

    public boolean platformDefaultAvailable(String provider) {
        return platformDefaults.containsKey(provider) && !platformDefaults.get(provider).isBlank();
    }

    public Status status(String dbName, String provider, String keyName) {
        JdbcTemplate jdbc = new JdbcTemplate(tenantDs.forDb(dbName));
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT value_ciphertext, use_platform_default FROM tenant_credentials "
                + "WHERE provider = ? AND key_name = ?", provider, keyName);
        boolean hasTenantValue = false;
        boolean useDefault = false;
        if (!rows.isEmpty()) {
            Object ct = rows.get(0).get("value_ciphertext");
            hasTenantValue = ct instanceof byte[] b && b.length > 0;
            Object ud = rows.get(0).get("use_platform_default");
            useDefault = (ud instanceof Boolean b ? b : (ud instanceof Number n && n.intValue() != 0));
        }
        return new Status(hasTenantValue, useDefault, platformDefaultAvailable(provider));
    }

    public Resolution resolve(String dbName, String provider, String keyName) {
        JdbcTemplate jdbc = new JdbcTemplate(tenantDs.forDb(dbName));
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT value_ciphertext, value_iv, value_tag, use_platform_default "
                + "FROM tenant_credentials WHERE provider = ? AND key_name = ?", provider, keyName);
        if (rows.isEmpty()) return new Resolution(null, Source.NONE);

        Map<String, Object> r = rows.get(0);
        Object ud = r.get("use_platform_default");
        boolean useDefault = (ud instanceof Boolean b ? b : (ud instanceof Number n && n.intValue() != 0));
        if (useDefault) {
            String def = platformDefaults.get(provider);
            return def != null && !def.isBlank()
                ? new Resolution(def, Source.PLATFORM_DEFAULT)
                : new Resolution(null, Source.NONE);
        }
        String decrypted = decryptRow(r);
        return decrypted != null && !decrypted.isBlank()
            ? new Resolution(decrypted, Source.TENANT)
            : new Resolution(null, Source.NONE);
    }

    public void set(String dbName, String provider, String keyName, String plaintext, String actorEmail, String actorRole) {
        if (plaintext == null) plaintext = "";
        Cipher result;
        byte[] iv = null;
        byte[] ciphertext;
        byte[] tag = null;
        if (encryptionEnabled) {
            iv = new byte[GCM_IV_BYTES];
            RNG.nextBytes(iv);
            try {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(masterKey, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
                byte[] combined = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
                // GCM appends tag at the end of the ciphertext; split for storage.
                int tagLen = GCM_TAG_BITS / 8;
                ciphertext = new byte[combined.length - tagLen];
                tag = new byte[tagLen];
                System.arraycopy(combined, 0, ciphertext, 0, ciphertext.length);
                System.arraycopy(combined, ciphertext.length, tag, 0, tagLen);
            } catch (Exception e) {
                throw new RuntimeException("encrypt failed", e);
            }
        } else {
            ciphertext = plaintext.getBytes(StandardCharsets.UTF_8);
        }

        JdbcTemplate jdbc = new JdbcTemplate(tenantDs.forDb(dbName));
        jdbc.update(
            "INSERT INTO tenant_credentials (provider, key_name, value_ciphertext, value_iv, value_tag, updated_by_email, updated_by_role) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE value_ciphertext = VALUES(value_ciphertext), value_iv = VALUES(value_iv), "
                + "value_tag = VALUES(value_tag), updated_by_email = VALUES(updated_by_email), updated_by_role = VALUES(updated_by_role)",
            provider, keyName, ciphertext, iv, tag, actorEmail, actorRole);
    }

    public void clear(String dbName, String provider, String keyName, String actorEmail, String actorRole) {
        JdbcTemplate jdbc = new JdbcTemplate(tenantDs.forDb(dbName));
        jdbc.update(
            "INSERT INTO tenant_credentials (provider, key_name, value_ciphertext, value_iv, value_tag, updated_by_email, updated_by_role) "
                + "VALUES (?, ?, NULL, NULL, NULL, ?, ?) "
                + "ON DUPLICATE KEY UPDATE value_ciphertext = NULL, value_iv = NULL, value_tag = NULL, "
                + "updated_by_email = VALUES(updated_by_email), updated_by_role = VALUES(updated_by_role)",
            provider, keyName, actorEmail, actorRole);
    }

    public void setUseDefault(String dbName, String provider, String keyName, boolean value, String actorEmail, String actorRole) {
        JdbcTemplate jdbc = new JdbcTemplate(tenantDs.forDb(dbName));
        jdbc.update(
            "INSERT INTO tenant_credentials (provider, key_name, use_platform_default, updated_by_email, updated_by_role) "
                + "VALUES (?, ?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE use_platform_default = VALUES(use_platform_default), "
                + "updated_by_email = VALUES(updated_by_email), updated_by_role = VALUES(updated_by_role)",
            provider, keyName, value, actorEmail, actorRole);
    }

    private String decryptRow(Map<String, Object> row) {
        Object ct = row.get("value_ciphertext");
        if (!(ct instanceof byte[] ciphertext) || ciphertext.length == 0) return null;
        if (!encryptionEnabled) {
            return new String(ciphertext, StandardCharsets.UTF_8);
        }
        Object ivObj = row.get("value_iv");
        Object tagObj = row.get("value_tag");
        if (!(ivObj instanceof byte[] iv) || !(tagObj instanceof byte[] tag)) {
            log.warn("tenant_credentials row has ciphertext but missing iv/tag — was it written with encryption disabled?");
            return null;
        }
        try {
            byte[] combined = new byte[ciphertext.length + tag.length];
            System.arraycopy(ciphertext, 0, combined, 0, ciphertext.length);
            System.arraycopy(tag, 0, combined, ciphertext.length, tag.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,
                new SecretKeySpec(masterKey, "AES"),
                new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(combined), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Failed to decrypt tenant_credentials row: {}", e.toString());
            return null;
        }
    }
}
