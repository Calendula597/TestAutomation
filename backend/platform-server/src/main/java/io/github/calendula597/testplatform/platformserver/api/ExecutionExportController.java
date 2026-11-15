package io.github.calendula597.testplatform.platformserver.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.calendula597.testplatform.platformserver.identity.UserPrincipal;
import io.github.calendula597.testplatform.platformserver.project.InsufficientRoleException;
import io.github.calendula597.testplatform.platformserver.project.ProjectAccessService;
import io.github.calendula597.testplatform.platformserver.project.ProjectMember;
import io.github.calendula597.testplatform.platformserver.project.ResourceNotFoundException;
import io.github.calendula597.testplatform.platformserver.reporting.ExecutionExportService;
import io.github.calendula597.testplatform.platformserver.worker.ExecutionAttempt;
import io.github.calendula597.testplatform.platformserver.worker.ExecutionAttemptMapper;
import io.github.calendula597.testplatform.platformserver.worker.ExecutionJob;
import io.github.calendula597.testplatform.platformserver.worker.ExecutionJobMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
public class ExecutionExportController {

    private final ProjectAccessService access;
    private final ExecutionJobMapper jobMapper;
    private final ExecutionAttemptMapper attemptMapper;
    private final ExecutionExportService exportService;
    private final ObjectMapper objectMapper;

    public ExecutionExportController(ProjectAccessService access, ExecutionJobMapper jobMapper,
                                     ExecutionAttemptMapper attemptMapper, ExecutionExportService exportService,
                                     ObjectMapper objectMapper) {
        this.access = access;
        this.jobMapper = jobMapper;
        this.attemptMapper = attemptMapper;
        this.exportService = exportService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/api/v1/projects/{projectId}/executions/{jobId}/export")
    public ResponseEntity<byte[]> export(@PathVariable Long projectId, @PathVariable Long jobId,
                                         @RequestParam String format, Authentication authentication) throws Exception {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        access.requireRole(projectId, principal.id(), ProjectMember.ROLE_VIEWER);
        ExecutionJob job = jobMapper.selectById(jobId);
        if (job == null || !projectId.equals(job.getProjectId())) {
            throw new ResourceNotFoundException("执行任务不存在");
        }
        List<Map<String, Object>> attempts = new ArrayList<>();
        for (ExecutionAttempt attempt : attemptMapper.selectList(new LambdaQueryWrapper<ExecutionAttempt>()
                .eq(ExecutionAttempt::getJobId, jobId).orderByAsc(ExecutionAttempt::getAttemptNumber))) {
            Map<String, Object> view = new java.util.LinkedHashMap<>();
            view.put("attemptNumber", attempt.getAttemptNumber());
            view.put("status", attempt.getStatus());
            view.put("durationMs", attempt.getResult() == null ? 0 : parseDuration(attempt));
            view.put("result", parseResult(attempt.getResult()));
            attempts.add(view);
        }
        ExecutionExportService.ExportPayload payload = exportService.export(job, attempts, format);
        return ResponseEntity.ok()
                .header("Content-Type", payload.contentType())
                .header("Content-Disposition", "attachment; filename=\"" + payload.filename() + "\"")
                .body(payload.content());
    }

    private long parseDuration(ExecutionAttempt attempt) {
        if (attempt.getStartedAt() == null || attempt.getFinishedAt() == null) {
            return 0;
        }
        return java.time.Duration.between(attempt.getStartedAt(), attempt.getFinishedAt()).toMillis();
    }

    private Object parseResult(String json) {
        if (json == null) {
            return null;
        }
        try {
            Object result = objectMapper.readValue(json, Object.class);
            if (result instanceof Map<?, ?> wrapped && wrapped.containsKey("detail")) {
                return wrapped.get("detail");
            }
            return result;
        } catch (Exception e) {
            return null;
        }
    }

    @ExceptionHandler({ResourceNotFoundException.class, InsufficientRoleException.class, IllegalArgumentException.class})
    public ResponseEntity<?> mapped(RuntimeException e) {
        HttpStatus status = e instanceof ResourceNotFoundException ? HttpStatus.NOT_FOUND
                : e instanceof InsufficientRoleException ? HttpStatus.FORBIDDEN : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status)
                .body(new IdentityDtos.ErrorResponse(status.value(), e.getMessage()));
    }
}
