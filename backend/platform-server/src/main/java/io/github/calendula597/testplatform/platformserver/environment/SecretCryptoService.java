package io.github.calendula597.testplatform.platformserver.environment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM encryption for environment secret values. The master key
 * arrives through configuration (APP_SECRETS_MASTER_KEY, base64, 32 bytes)
 * and is never persisted or logged.
 *
 * Ciphertext format: v1:&lt;base64 iv&gt;:&lt;base64 ciphertext+tag&gt;.
 * Without a configured key, encryption and decryption fail explicitly —
 * plaintext storage is never a fallback. Masked API responses never call
 * {@link #decrypt}; only execution-time value resolution inside Workers
 * does.
 */
@Service
public class SecretCryptoService {

    private static final String CIPHERTEXT_PREFIX = "v1:";
    private static final int IV_LENGTH = 12;
    private static final int KEY_LENGTH = 32;
    private static final int TAG_BITS = 128;

    private final SecretKey masterKey;
    private final SecureRandom random = new SecureRandom();

    public SecretCryptoService(@Value("${app.secrets.master-key:}") String masterKeyBase64) {
        if (masterKeyBase64 == null || masterKeyBase64.isBlank()) {
            this.masterKey = null;
            return;
        }
        byte[] decoded = Base64.getDecoder().decode(masterKeyBase64.trim());
        if (decoded.length != KEY_LENGTH) {
            throw new IllegalStateException("密钥主密钥必须是 32 字节的 base64 编码");
        }
        this.masterKey = new SecretKeySpec(decoded, "AES");
    }

    public boolean isConfigured() {
        return masterKey != null;
    }

    public String encrypt(String plaintext) {
        requireConfigured();
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, masterKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return CIPHERTEXT_PREFIX
                    + Base64.getEncoder().encodeToString(iv) + ":"
                    + Base64.getEncoder().encodeToString(ciphertext);
        } catch (Exception e) {
            throw new IllegalStateException("密钥加密失败", e);
        }
    }

    public String decrypt(String ciphertext) {
        requireConfigured();
        String[] parts = ciphertext.split(":");
        if (parts.length != 3 || !CIPHERTEXT_PREFIX.equals(parts[0] + ":")) {
            throw new IllegalStateException("密文格式无效");
        }
        try {
            byte[] iv = Base64.getDecoder().decode(parts[1]);
            byte[] encrypted = Base64.getDecoder().decode(parts[2]);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, masterKey, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("密钥解密失败", e);
        }
    }

    private void requireConfigured() {
        if (masterKey == null) {
            throw new IllegalStateException("密钥加密未配置主密钥");
        }
    }
}
