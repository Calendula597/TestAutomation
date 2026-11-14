package io.github.calendula597.testplatform.platformserver.environment;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Allowlist semantics at the pure-logic seam: origin canonicalization and
 * redirect re-validation. Endpoint behavior is covered by the API test.
 */
class TargetAllowlistTest {

    private final TargetAllowlist allowlist = new TargetAllowlist();

    @Test
    void normalizesTargetsToCanonicalOrigins() {
        assertThat(allowlist.normalizeTarget("https://api.demo.local"))
                .isEqualTo("https://api.demo.local");
        assertThat(allowlist.normalizeTarget("HTTPS://Api.Demo.LOCAL"))
                .isEqualTo("https://api.demo.local");
        assertThat(allowlist.normalizeTarget("http://127.0.0.1:8080"))
                .isEqualTo("http://127.0.0.1:8080");
        assertThat(allowlist.normalizeTarget("https://api.demo.local:443"))
                .isEqualTo("https://api.demo.local");
        assertThat(allowlist.normalizeTarget("http://api.demo.local:80"))
                .isEqualTo("http://api.demo.local");
    }

    @Test
    void rejectsNonOriginTargets() {
        assertThatThrownBy(() -> allowlist.normalizeTarget("ftp://files.demo.local"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> allowlist.normalizeTarget("https://api.demo.local/v1/login"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> allowlist.normalizeTarget("https://user@api.demo.local"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> allowlist.normalizeTarget("notaurl"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> allowlist.normalizeTarget(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void matchesByOriginIgnoringPathsAndQuery() {
        List<String> targets = List.of("https://api.demo.local", "http://127.0.0.1:8080");

        TargetAllowlist.Check hit = allowlist.check(targets,
                "https://api.demo.local/v1/login?next=%2Fhome");
        assertThat(hit.allowed()).isTrue();
        assertThat(hit.matchedTarget()).isEqualTo("https://api.demo.local");

        assertThat(allowlist.check(targets, "https://other.demo.local/v1").allowed()).isFalse();
        // Same host but wrong scheme or port does not match.
        assertThat(allowlist.check(targets, "http://api.demo.local/x").allowed()).isFalse();
        assertThat(allowlist.check(targets, "https://api.demo.local:8443/x").allowed()).isFalse();
        assertThat(allowlist.check(targets, "http://127.0.0.1:8080/x").allowed()).isTrue();
    }

    @Test
    void redirectHopsAreCheckedAsTheirOwnOrigins() {
        List<String> targets = List.of("https://api.demo.local");
        assertThat(allowlist.check(targets, "http://evil.example.com/collect").allowed()).isFalse();
        assertThat(allowlist.check(targets, "https://api.demo.local").allowed()).isTrue();
    }
}
