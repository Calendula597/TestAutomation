package io.github.calendula597.testplatform.platformserver.api;

import io.github.calendula597.testplatform.platformserver.identity.UserPrincipal;
import io.github.calendula597.testplatform.platformserver.project.InsufficientRoleException;
import io.github.calendula597.testplatform.platformserver.project.ProjectAccessService;
import io.github.calendula597.testplatform.platformserver.project.ProjectMember;
import io.github.calendula597.testplatform.platformserver.project.ResourceNotFoundException;
import io.github.calendula597.testplatform.platformserver.reporting.EvidenceRetentionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class EvidenceRetentionController {

    private final ProjectAccessService access;
    private final EvidenceRetentionService retentionService;

    public EvidenceRetentionController(ProjectAccessService access, EvidenceRetentionService retentionService) {
        this.access = access;
        this.retentionService = retentionService;
    }

    public record PolicyRequest(Integer retentionDays) {
    }

    @GetMapping("/api/v1/projects/{projectId}/evidence-policy")
    public Map<String, Object> get(@PathVariable Long projectId, Authentication authentication) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        access.requireRole(projectId, principal.id(), ProjectMember.ROLE_OWNER);
        return Map.of("retentionDays", retentionService.retentionDays(projectId));
    }

    @PutMapping("/api/v1/projects/{projectId}/evidence-policy")
    public Map<String, Object> update(@PathVariable Long projectId, @RequestBody PolicyRequest body,
                                      Authentication authentication) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        access.requireRole(projectId, principal.id(), ProjectMember.ROLE_OWNER);
        if (body.retentionDays() == null) {
            throw new IllegalArgumentException("必须指定保留天数");
        }
        retentionService.updateRetentionDays(projectId, body.retentionDays(), principal.id());
        return Map.of("retentionDays", retentionService.retentionDays(projectId));
    }

    @PostMapping("/api/v1/system/evidence-retention/sweep")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> sweep() throws Exception {
        return ResponseEntity.ok(retentionService.sweepOnce());
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
