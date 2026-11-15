package io.github.calendula597.testplatform.platformserver.api;

import io.github.calendula597.testplatform.platformserver.automation.ScheduleService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/schedules")
public class ScheduleController {
    private final ScheduleService scheduleService;
    private final ProjectAccessService access;
    private final AuditService auditService;

    public ScheduleController(ScheduleService scheduleService, ProjectAccessService access, AuditService auditService) {
        this.scheduleService = scheduleService;
        this.access = access;
        this.auditService = auditService;
    }

    @PostMapping
    public ResponseEntity<?> create(@PathVariable Long projectId, @RequestBody ScheduleService.ScheduleCommand body,
                                    Authentication authentication) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        access.requireRole(projectId, principal.id(), ProjectMember.ROLE_MEMBER);
        var schedule = scheduleService.create(projectId, body, principal.id());
        auditService.record(authentication, "SCHEDULE_CREATED", null,
                Map.of("projectId", projectId, "scheduleId", schedule.getId()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ScheduleService.view(schedule));
    }

    @GetMapping
    public List<Map<String, Object>> list(@PathVariable Long projectId, Authentication authentication) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        access.requireRole(projectId, principal.id(), ProjectMember.ROLE_VIEWER);
        return scheduleService.list(projectId).stream().map(ScheduleService::view).toList();
    }

    @PutMapping("/{scheduleId}")
    public Map<String, Object> update(@PathVariable Long projectId, @PathVariable Long scheduleId,
                                      @RequestBody ScheduleService.ScheduleCommand body,
                                      Authentication authentication) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        access.requireRole(projectId, principal.id(), ProjectMember.ROLE_MEMBER);
        var schedule = scheduleService.update(projectId, scheduleId, body, principal.id());
        auditService.record(authentication, "SCHEDULE_UPDATED", null,
                Map.of("projectId", projectId, "scheduleId", scheduleId));
        return ScheduleService.view(schedule);
    }

    @DeleteMapping("/{scheduleId}")
    public ResponseEntity<Void> disable(@PathVariable Long projectId, @PathVariable Long scheduleId,
                                        Authentication authentication) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        access.requireRole(projectId, principal.id(), ProjectMember.ROLE_MEMBER);
        scheduleService.disable(projectId, scheduleId);
        auditService.record(authentication, "SCHEDULE_DISABLED", null,
                Map.of("projectId", projectId, "scheduleId", scheduleId));
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(new IdentityDtos.ErrorResponse(400, e.getMessage()));
    }

    @ExceptionHandler(ScheduleService.ScheduleConflictException.class)
    public ResponseEntity<?> conflict(ScheduleService.ScheduleConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new IdentityDtos.ErrorResponse(409, e.getMessage()));
    }

    @ExceptionHandler({ResourceNotFoundException.class, InsufficientRoleException.class})
    public ResponseEntity<?> mapped(RuntimeException e) {
        HttpStatus status = e instanceof ResourceNotFoundException ? HttpStatus.NOT_FOUND : HttpStatus.FORBIDDEN;
        return ResponseEntity.status(status).body(new IdentityDtos.ErrorResponse(status.value(), e.getMessage()));
    }
}
