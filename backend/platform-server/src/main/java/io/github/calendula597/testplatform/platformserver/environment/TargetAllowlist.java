package io.github.calendula597.testplatform.platformserver.environment;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.List;

/**
 * Target allowlist semantics. A target is a fixed origin
 * {@code scheme://host[:port]} with http or https; matching compares
 * scheme, host and effective port and ignores paths and query strings.
 *
 * Redirect hops are re-validated as their own origins: an allowed start
 * URL never whitelists where a request ends up, so executors must check
 * every hop through {@link #check} before following it.
 */
@Component
public class TargetAllowlist {

    private static final List<String> ALLOWED_SCHEMES = List.of("http", "https");

    public record Check(String url, boolean allowed, String matchedTarget, String reason) {
    }

    /**
     * Normalizes a configured target to its canonical origin. Rejects
     * anything that is not a plain http/https origin — targets are
     * infrastructure identifiers, not request URLs.
     */
    public String normalizeTarget(String raw) {
        URI uri = parse(raw, "目标格式无效");
        if (uri.getUserInfo() != null || uri.getPath() != null && !uri.getPath().isEmpty()
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("目标只能是 scheme://host[:port] 形式的来源");
        }
        return canonical(uri);
    }

    /**
     * Validates one request URL (or redirect hop) against the allowlist.
     * Malformed or non-http(s) URLs throw so the caller answers 400; a
     * well-formed URL outside the allowlist is a legitimate negative
     * check result.
     */
    public Check check(List<String> normalizedTargets, String url) {
        URI uri = parse(url, "URL 无法解析或不是 http/https 绝对地址");
        String origin = canonical(uri);
        return normalizedTargets.stream()
                .filter(origin::equals)
                .findFirst()
                .map(target -> new Check(url, true, target, null))
                .orElse(new Check(url, false, null, "目标不在白名单"));
    }

    private URI parse(String raw, String message) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        try {
            URI uri = new URI(raw.trim());
            if (uri.getScheme() == null || !ALLOWED_SCHEMES.contains(uri.getScheme().toLowerCase())
                    || uri.getHost() == null) {
                throw new IllegalArgumentException(message);
            }
            return uri;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException(message);
        }
    }

    /** scheme://host[:port] with the host lowercased and default ports omitted. */
    private String canonical(URI uri) {
        String scheme = uri.getScheme().toLowerCase();
        String host = uri.getHost().toLowerCase();
        int port = uri.getPort();
        boolean defaultPort = (port == -1) || ("http".equals(scheme) && port == 80)
                || ("https".equals(scheme) && port == 443);
        return defaultPort ? scheme + "://" + host : scheme + "://" + host + ":" + port;
    }
}
