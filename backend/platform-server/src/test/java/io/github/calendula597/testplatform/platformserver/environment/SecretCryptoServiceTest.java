package io.github.calendula597.testplatform.platformserver.environment;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Secret crypto at the pure seam: AES-GCM round trip, ciphertext format
 * and explicit failures instead of plaintext fallbacks.
 */
class SecretCryptoServiceTest {

    private static String keyOf(int bytes) {
        byte[] key = new byte[bytes];
        new java.security.SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    @Test
    void roundTripsPlaintextThroughCiphertext() {
        SecretCryptoService crypto = new SecretCryptoService(keyOf(32));
        assertThat(crypto.isConfigured()).isTrue();

        String ciphertext = crypto.encrypt("sk-super-secret-001");
        assertThat(ciphertext).startsWith("v1:").doesNotContain("sk-super-secret-001");
        assertThat(crypto.decrypt(ciphertext)).isEqualTo("sk-super-secret-001");

        // Each encryption uses a fresh IV.
        assertThat(crypto.encrypt("sk-super-secret-001")).isNotEqualTo(ciphertext);
    }

    @Test
    void withoutMasterKeyEveryOperationFailsExplicitly() {
        SecretCryptoService unconfigured = new SecretCryptoService(" ");
        assertThat(unconfigured.isConfigured()).isFalse();
        assertThatThrownBy(() -> unconfigured.encrypt("value"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未配置主密钥");
        assertThatThrownBy(() -> unconfigured.decrypt("v1:aa:bb"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未配置主密钥");
    }

    @Test
    void rejectsInvalidKeysAndCiphertext() {
        assertThatThrownBy(() -> new SecretCryptoService(keyOf(16)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 字节");

        SecretCryptoService crypto = new SecretCryptoService(keyOf(32));
        assertThatThrownBy(() -> crypto.decrypt("plaintext"))
                .isInstanceOf(IllegalStateException.class);
        // Tampered ciphertext fails authentication instead of returning data.
        String ciphertext = crypto.encrypt("sk-super-secret-001");
        String tampered = ciphertext.substring(0, ciphertext.length() - 2) + "AA";
        assertThatThrownBy(() -> crypto.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class);
    }
}
