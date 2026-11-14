package io.github.calendula597.testplatform.platformserver.api;

import io.github.calendula597.testplatform.platformserver.identity.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Session login/logout for the Vue frontend. Sessions are server-side
 * (JDBC-backed) and travel in an HttpOnly cookie; CSRF is enforced on all
 * mutating calls.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    /** Single, identical failure message so callers cannot probe usernames. */
    private static final String LOGIN_FAILED_MESSAGE = "用户名或密码不正确，或账号已被禁用";

    public record ErrorResponse(int status, String message) {
    }

    private final AuthenticationManager authenticationManager;
    private final AuditService auditService;
    private final SecurityContextRepository securityContextRepository;

    public AuthController(AuthenticationManager authenticationManager, AuditService auditService,
                          SecurityContextRepository securityContextRepository) {
        this.authenticationManager = authenticationManager;
        this.auditService = auditService;
        this.securityContextRepository = securityContextRepository;
    }

    public record CsrfResponse(String headerName) {
    }

    @GetMapping("/csrf")
    public CsrfResponse csrf(CsrfToken token) {
        // Injecting the parameter resolves the deferred token and writes the
        // XSRF-TOKEN cookie. The SPA sends the COOKIE value (raw token) in
        // the X-XSRF-TOKEN header — getToken() here is the BREACH-encoded
        // value and must never be used by the client.
        return new CsrfResponse(token.getHeaderName());
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody IdentityDtos.LoginRequest body,
                                   HttpServletRequest request, HttpServletResponse response) {
        try {
            Authentication authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(body.username(), body.password()));
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            securityContextRepository.saveContext(context, request, response);
            auditService.record(authentication, "LOGIN", null, Map.of());
            IdentityDtos.MeResponse me = new IdentityDtos.MeResponse(authentication.getName(), roleOf(authentication));
            return ResponseEntity.ok(me);
        } catch (AuthenticationException e) {
            auditService.record(null, "LOGIN_FAILED", null, Map.of("username", body.username() == null ? "" : body.username()));
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new IdentityDtos.ErrorResponse(401, LOGIN_FAILED_MESSAGE));
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        // Invalidate the session and clear the security context.
        new org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler()
                .logout(request, response, authentication);
        auditService.record(authentication, "LOGOUT", null, Map.of());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public IdentityDtos.MeResponse me(Authentication authentication) {
        return new IdentityDtos.MeResponse(authentication.getName(), roleOf(authentication));
    }

    private static String roleOf(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(a -> a.getAuthority())
                .filter(a -> a.startsWith("ROLE_"))
                .findFirst()
                .map(a -> a.substring("ROLE_".length()))
                .orElse("");
    }
}
