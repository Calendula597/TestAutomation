package io.github.calendula597.testplatform.platformserver.api;

import io.github.calendula597.testplatform.platformserver.automation.CiTokenService;
import io.github.calendula597.testplatform.platformserver.identity.AuditService;
import io.github.calendula597.testplatform.platformserver.identity.UserPrincipal;
import io.github.calendula597.testplatform.platformserver.project.InsufficientRoleException;
import io.github.calendula597.testplatform.platformserver.project.ProjectAccessService;
import io.github.calendula597.testplatform.platformserver.project.ProjectMember;
import io.github.calendula597.testplatform.platformserver.project.ResourceNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/ci-tokens")
public class CiTokenController {
    private final CiTokenService tokenService;
    private final ProjectAccessService access;
    private final AuditService auditService;

    public CiTokenController(CiTokenService tokenService, ProjectAccessService access, AuditService auditService) {
        this.tokenService = tokenService;
        this.access = access;
        this.auditService = auditService;
    }

    public record CreateTokenRequest(String name, List<String> scopes, OffsetDateTime expiresAt) {
    }

    @PostMapping
    public ResponseEntity<?> create(@PathVariable Long projectId, @RequestBody CreateTokenRequest body,
                                    Authentication authentication) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        access.requireRole(projectId, principal.id(), ProjectMember.ROLE_OWNER);
        var issued = tokenService.issue(projectId, body.name(), body.scopes(), body.expiresAt(), principal.id());
        auditService.record(authentication, "CI_TOKEN_ISSUED", null,
                Map.of("projectId", projectId, "tokenId", issued.metadata().getId(),
                        "scopes", issued.scopes(), "expiresAt", issued.metadata().getExpiresAt().toString()));
        Map<String, Object> response = tokenService.view(issued.metadata());
        response.put("token", issued.token());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public List<Map<String, Object>> list(@PathVariable Long projectId, Authentication authentication) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        access.requireRole(projectId, principal.id(), ProjectMember.ROLE_OWNER);
        return tokenService.list(projectId);
    }

    @DeleteMapping("/{tokenId}")
    public ResponseEntity<Void> revoke(@PathVariable Long projectId, @PathVariable Long tokenId,
                                       Authentication authentication) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        access.requireRole(projectId, principal.id(), ProjectMember.ROLE_OWNER);
        tokenService.revoke(projectId, tokenId);
        auditService.record(authentication, "CI_TOKEN_REVOKED", null,
                Map.of("projectId", projectId, "tokenId", tokenId));
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<?> duplicate(org.springframework.dao.DataIntegrityViolationException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new IdentityDtos.ErrorResponse(409, "同名 CI 令牌已存在"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(new IdentityDtos.ErrorResponse(400, e.getMessage()));
    }

    @ExceptionHandler({ResourceNotFoundException.class, InsufficientRoleException.class})
    public ResponseEntity<?> mapped(RuntimeException e) {
        HttpStatus status = e instanceof ResourceNotFoundException ? HttpStatus.NOT_FOUND : HttpStatus.FORBIDDEN;
        return ResponseEntity.status(status).body(new IdentityDtos.ErrorResponse(status.value(), e.getMessage()));
    }
}
