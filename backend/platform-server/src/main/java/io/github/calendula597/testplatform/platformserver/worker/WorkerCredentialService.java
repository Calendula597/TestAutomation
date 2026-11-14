package io.github.calendula597.testplatform.platformserver.worker;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Worker credentials: opaque bearer tokens with a "wpk_" prefix, shown
 * once at registration and stored only as SHA-256 digests. The credential
 * grants nothing except the worker protocol endpoints — the minimal
 * privilege a worker needs.
 */
@Service
public class WorkerCredentialService {

    private static final String TOKEN_PREFIX = "wpk_";
    private static final String LEASE_PREFIX = "wpk_lease_";

    private final SecureRandom random = new SecureRandom();

    public record IssuedToken(String token, String tokenHash) {
    }

    public IssuedToken issueToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = TOKEN_PREFIX + HexFormat.of().formatHex(bytes);
        return new IssuedToken(token, hash(token));
    }

    public String newLeaseToken() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return LEASE_PREFIX + HexFormat.of().formatHex(bytes);
    }

    public String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
