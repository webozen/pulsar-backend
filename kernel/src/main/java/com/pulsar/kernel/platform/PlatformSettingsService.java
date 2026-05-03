package com.pulsar.kernel.platform;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Platform-level (cross-tenant) settings store. Mirrors {@link
 * com.pulsar.kernel.credentials.CredentialsService} but operates on the
 * platform DB (Spring's primary DataSource) instead of per-tenant DBs, and is
 * keyed by a single {@code setting_key} instead of {@code (provider, key_name)}.
 *
 * <p>Use it for shared infrastructure secrets that don't belong in per-tenant
 * scope: AnythingLLM URL + API key, future shared LLM provider defaults, etc.
 *
 * <p>{@link #resolveOrFallback(String, String)} returns the DB value when set,
 * otherwise falls back to the supplied env-driven value (typically a Spring
 * {@code @Value("${...}")} string). This keeps the dev {@code .env} flow
 * working unchanged and avoids forcing operators to migrate at deploy time.
 */
@Service
public class PlatformSettingsService {
    private static final Logger log = LoggerFactory.getLogger(PlatformSettingsService.class);
    private static final SecureRandom RNG = new SecureRandom();
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    /** Whitelist of known setting keys. PUTs to anything else 400 fast. */
    public static final Set<String> KNOWN_KEYS = Set.of(
        "anythingllm.url",
        "anythingllm.api_key"
    );

    public record Status(boolean hasValue, boolean envFallbackAvailable) {}

    private final DataSource platformDs;
    private final byte[] masterKey;
    private final boolean encryptionEnabled;

    public PlatformSettingsService(
        DataSource platformDs,
        @Value("${pulsar.credentials.master-key:}") String masterKeyB64,
        Environment environment
    ) {
        this.platformDs = platformDs;
        boolean prodLike = isProdLikeProfile(environment);
        if (masterKeyB64 == null || masterKeyB64.isBlank()) {
            if (prodLike) {
                throw new IllegalStateException(
                    "PULSAR_CREDENTIALS_MASTER_KEY must be set in prod-like profiles; "
                        + "PlatformSettingsService refuses to start without it.");
            }
            this.masterKey = null;
            this.encryptionEnabled = false;
            log.warn("PULSAR_CREDENTIALS_MASTER_KEY not set — platform_settings values will be plaintext (dev only).");
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
    }

    public boolean encryptionEnabled() { return encryptionEnabled; }

    /**
     * @return DB value if set, otherwise the fallback (typically the env-injected
     *         value). Returns null only when both DB and fallback are blank.
     */
    public String resolveOrFallback(String settingKey, String envFallback) {
        String dbValue = resolve(settingKey);
        if (dbValue != null && !dbValue.isBlank()) return dbValue;
        return envFallback;
    }

    public String resolve(String settingKey) {
        JdbcTemplate jdbc = new JdbcTemplate(platformDs);
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT value_ciphertext, value_iv, value_tag FROM platform_settings WHERE setting_key = ?",
            settingKey);
        if (rows.isEmpty()) return null;
        return decryptRow(rows.get(0));
    }

    public Status status(String settingKey, String envFallback) {
        JdbcTemplate jdbc = new JdbcTemplate(platformDs);
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM platform_settings WHERE setting_key = ? AND LENGTH(value_ciphertext) > 0",
            Integer.class, settingKey);
        boolean hasValue = n != null && n > 0;
        boolean envAvailable = envFallback != null && !envFallback.isBlank();
        return new Status(hasValue, envAvailable);
    }

    public void set(String settingKey, String plaintext, String actorEmail) {
        if (!KNOWN_KEYS.contains(settingKey)) {
            throw new IllegalArgumentException("unknown platform setting key: " + settingKey);
        }
        if (plaintext == null) plaintext = "";
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
        new JdbcTemplate(platformDs).update(
            "INSERT INTO platform_settings (setting_key, value_ciphertext, value_iv, value_tag, updated_by_email) "
                + "VALUES (?, ?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE value_ciphertext = VALUES(value_ciphertext), "
                + "value_iv = VALUES(value_iv), value_tag = VALUES(value_tag), "
                + "updated_by_email = VALUES(updated_by_email)",
            settingKey, ciphertext, iv, tag, actorEmail);
    }

    public void clear(String settingKey, String actorEmail) {
        if (!KNOWN_KEYS.contains(settingKey)) {
            throw new IllegalArgumentException("unknown platform setting key: " + settingKey);
        }
        new JdbcTemplate(platformDs).update(
            "INSERT INTO platform_settings (setting_key, value_ciphertext, value_iv, value_tag, updated_by_email) "
                + "VALUES (?, NULL, NULL, NULL, ?) "
                + "ON DUPLICATE KEY UPDATE value_ciphertext = NULL, value_iv = NULL, value_tag = NULL, "
                + "updated_by_email = VALUES(updated_by_email)",
            settingKey, actorEmail);
    }

    private String decryptRow(Map<String, Object> row) {
        Object ct = row.get("value_ciphertext");
        if (!(ct instanceof byte[] ciphertext) || ciphertext.length == 0) return null;
        if (!encryptionEnabled) return new String(ciphertext, StandardCharsets.UTF_8);

        Object ivObj = row.get("value_iv");
        Object tagObj = row.get("value_tag");
        if (!(ivObj instanceof byte[] iv) || !(tagObj instanceof byte[] tag)) return null;

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
            log.error("Failed to decrypt platform_settings row [{}]: {}", row.keySet(), e.toString());
            return null;
        }
    }

    private static boolean isProdLikeProfile(Environment environment) {
        String[] active = environment.getActiveProfiles();
        boolean hasProd = Arrays.stream(active).anyMatch(p -> p.equalsIgnoreCase("prod"));
        boolean hasDevLike = Arrays.stream(active).anyMatch(p -> {
            String lower = p.toLowerCase();
            return lower.startsWith("dev") || lower.startsWith("local") || lower.startsWith("test");
        });
        return hasProd || (active.length > 0 && !hasDevLike);
    }
}
