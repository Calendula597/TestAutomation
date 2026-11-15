package io.github.calendula597.testplatform.platformserver.api;

import io.github.calendula597.testplatform.platformserver.automation.CiException;
import io.github.calendula597.testplatform.platformserver.automation.CiExecutionService;
import io.github.calendula597.testplatform.platformserver.automation.CiTokenService;
import io.github.calendula597.testplatform.platformserver.project.ResourceNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/ci/v1/projects/{projectId}/executions")
public class CiExecutionController {
    private final CiTokenService tokenService;
    private final CiExecutionService executionService;

    public CiExecutionController(CiTokenService tokenService, CiExecutionService executionService) {
        this.tokenService = tokenService;
        this.executionService = executionService;
    }

    public record TriggerRequest(Long caseId, Long environmentId, Integer timeoutSeconds) {
    }

    @PostMapping
    public ResponseEntity<?> trigger(@PathVariable Long projectId, @RequestBody TriggerRequest body,
                                     @RequestHeader("Idempotency-Key") String idempotencyKey,
                                     HttpServletRequest request) {
        var token = tokenService.authenticate(projectId, CiTokenService.TRIGGER,
                request.getHeader("Authorization"));
        var result = executionService.trigger(token, body.caseId(), body.environmentId(),
                body.timeoutSeconds(), idempotencyKey);
        Map<String, Object> response = Map.of("jobId", result.job().getId(),
                "status", result.job().getStatus(), "idempotent", result.idempotent());
        return ResponseEntity.status(result.idempotent() ? HttpStatus.OK : HttpStatus.ACCEPTED).body(response);
    }

    @GetMapping("/{jobId}")
    public Map<String, Object> detail(@PathVariable Long projectId, @PathVariable Long jobId,
                                      HttpServletRequest request) {
        tokenService.authenticate(projectId, CiTokenService.READ, request.getHeader("Authorization"));
        return executionService.detail(projectId, jobId);
    }

    @ExceptionHandler(CiException.class)
    public ResponseEntity<?> ciError(CiException e) {
        return ResponseEntity.status(e.status()).body(new IdentityDtos.ErrorResponse(e.status(), e.getMessage()));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<?> notFound(ResourceNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new IdentityDtos.ErrorResponse(404, e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(new IdentityDtos.ErrorResponse(400, e.getMessage()));
    }
}
