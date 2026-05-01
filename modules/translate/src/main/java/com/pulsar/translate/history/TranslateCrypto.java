package com.pulsar.translate.history;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM encryption for translate conversation transcripts. Key is loaded
 * once at construction from a base64-encoded 32-byte env value. IV is fresh
 * per row (12 bytes); the auth tag is split off the ciphertext so the DB
 * schema can store iv / tag / ciphertext separately for clarity.
 */
public class TranslateCrypto {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int TAG_BYTES = TAG_BITS / 8;

    private final SecretKey key;
    private final SecureRandom rng = new SecureRandom();

    public TranslateCrypto(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException("history-key not configured");
        }
        byte[] keyBytes = Base64.getDecoder().decode(base64Key);
        if (keyBytes.length != 32) {
            throw new IllegalStateException("history-key must decode to 32 bytes (AES-256), got " + keyBytes.length);
        }
        this.key = new SecretKeySpec(keyBytes, "AES");
    }

    public Encrypted encrypt(byte[] plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            rng.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] combined = cipher.doFinal(plaintext);
            // GCM appends the tag to the ciphertext output. Split it.
            byte[] ciphertext = new byte[combined.length - TAG_BYTES];
            byte[] tag = new byte[TAG_BYTES];
            System.arraycopy(combined, 0, ciphertext, 0, ciphertext.length);
            System.arraycopy(combined, ciphertext.length, tag, 0, TAG_BYTES);
            return new Encrypted(ciphertext, iv, tag);
        } catch (Exception e) {
            throw new RuntimeException("encrypt failed", e);
        }
    }

    public byte[] decrypt(byte[] ciphertext, byte[] iv, byte[] tag) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] combined = ByteBuffer.allocate(ciphertext.length + tag.length)
                .put(ciphertext).put(tag).array();
            return cipher.doFinal(combined);
        } catch (Exception e) {
            throw new RuntimeException("decrypt failed", e);
        }
    }

    public record Encrypted(byte[] ciphertext, byte[] iv, byte[] tag) {}
}
