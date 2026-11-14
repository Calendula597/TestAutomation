package io.github.calendula597.testplatform.platformserver.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.calendula597.testplatform.platformserver.identity.AuditService;
import io.github.calendula597.testplatform.platformserver.identity.UserPrincipal;
import io.github.calendula597.testplatform.platformserver.project.InsufficientRoleException;
import io.github.calendula597.testplatform.platformserver.project.ProjectAccessService;
import io.github.calendula597.testplatform.platformserver.project.ProjectMember;
import io.github.calendula597.testplatform.platformserver.project.ResourceNotFoundException;
import io.github.calendula597.testplatform.platformserver.requirement.Requirement;
import io.github.calendula597.testplatform.platformserver.requirement.RequirementMapper;
import io.github.calendula597.testplatform.platformserver.requirement.RequirementVersion;
import io.github.calendula597.testplatform.platformserver.requirement.RequirementVersionMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
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
import java.util.Set;

/**
 * Project-scoped requirement API. Versions are append-only: an update
 * inserts a new version row and never touches existing ones.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/requirements")
public class RequirementController {

    private static final Set<String> SOURCE_TYPES = Set.of(
            RequirementVersion.SOURCE_PASTED_TEXT, RequirementVersion.SOURCE_MARKDOWN);

    private final ProjectAccessService access;
    private final RequirementMapper requirementMapper;
    private final RequirementVersionMapper versionMapper;
    private final AuditService auditService;

    public RequirementController(ProjectAccessService access, RequirementMapper requirementMapper,
                                 RequirementVersionMapper versionMapper, AuditService auditService) {
        this.access = access;
        this.requirementMapper = requirementMapper;
        this.versionMapper = versionMapper;
        this.auditService = auditService;
    }

    public record CreateRequest(String title, String body, String sourceType, String acceptanceCriteria) {
    }

    public record UpdateRequest(String title, String body, String sourceType, String acceptanceCriteria) {
    }

    public record VersionView(int version, String body, String sourceType, String acceptanceCriteria,
                              String createdBy, String createdAt) {
    }

    public record RequirementView(Long id, String title, int currentVersion, String body,
                                  String sourceType, String acceptanceCriteria) {
    }

    @PostMapping
    @Transactional
    public ResponseEntity<?> create(@PathVariable Long projectId,
                                    @RequestBody CreateRequest body, Authentication authentication) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        access.requireRole(projectId, principal.id(), ProjectMember.ROLE_MEMBER);
        ResponseEntity<?> validation = validateContent(body.title(), body.body(), body.sourceType(),
                body.acceptanceCriteria());
        if (validation != null) {
            return validation;
        }

        Requirement requirement = new Requirement();
        requirement.setProjectId(projectId);
        requirement.setTitle(body.title().trim());
        requirement.setCurrentVersion(1);
        requirement.setCreatedBy(principal.id());
        requirementMapper.insert(requirement);
        insertVersion(requirement.getId(), 1, body.body(), body.sourceType(),
                body.acceptanceCriteria(), principal.id());

        auditService.record(authentication, "REQUIREMENT_CREATED", null,
                Map.of("projectId", projectId, "requirementId", requirement.getId(), "version", 1));
        return ResponseEntity.status(HttpStatus.CREATED).body(loadView(requirement));
    }

    @GetMapping
    public List<RequirementView> list(@PathVariable Long projectId, Authentication authentication) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        access.requireRole(projectId, principal.id(), ProjectMember.ROLE_VIEWER);
        return requirementMapper.selectList(new LambdaQueryWrapper<Requirement>()
                        .eq(Requirement::getProjectId, projectId)
                        .orderByDesc(Requirement::getId))
                .stream()
                .map(this::loadView)
                .toList();
    }

    @GetMapping("/{requirementId}")
    public ResponseEntity<?> detail(@PathVariable Long projectId, @PathVariable Long requirementId,
                                    Authentication authentication) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        access.requireRole(projectId, principal.id(), ProjectMember.ROLE_VIEWER);
        Requirement requirement = requireRequirement(projectId, requirementId);
        return ResponseEntity.ok(loadView(requirement));
    }

    @PutMapping("/{requirementId}")
    @Transactional
    public ResponseEntity<?> update(@PathVariable Long projectId, @PathVariable Long requirementId,
                                    @RequestBody UpdateRequest body, Authentication authentication) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        access.requireRole(projectId, principal.id(), ProjectMember.ROLE_MEMBER);
        Requirement requirement = requireRequirement(projectId, requirementId);

        boolean contentChanged = body.body() != null || body.sourceType() != null
                || body.acceptanceCriteria() != null;
        if (contentChanged) {
            String newBody = body.body() != null ? body.body() : currentOf(requirement).getBody();
            String newSource = body.sourceType() != null ? body.sourceType()
                    : currentOf(requirement).getSourceType();
            String newCriteria = body.acceptanceCriteria() != null ? body.acceptanceCriteria()
                    : currentOf(requirement).getAcceptanceCriteria();
            ResponseEntity<?> validation = validateContent(body.title() != null ? body.title()
                    : requirement.getTitle(), newBody, newSource, newCriteria);
            if (validation != null) {
                return validation;
            }
            int nextVersion = requirement.getCurrentVersion() + 1;
            insertVersion(requirementId, nextVersion, newBody, newSource, newCriteria, principal.id());
            requirement.setCurrentVersion(nextVersion);
        }
        if (body.title() != null) {
            if (body.title().isBlank() || body.title().length() > 200) {
                return badRequest("需求标题不能为空且不超过 200 字");
            }
            requirement.setTitle(body.title().trim());
        }
        requirementMapper.updateById(requirement);

        auditService.record(authentication, "REQUIREMENT_UPDATED", null,
                Map.of("projectId", projectId, "requirementId", requirementId,
                        "newVersion", requirement.getCurrentVersion()));
        return ResponseEntity.ok(loadView(requirement));
    }

    @GetMapping("/{requirementId}/versions")
    public List<VersionView> versions(@PathVariable Long projectId, @PathVariable Long requirementId,
                                      Authentication authentication) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        access.requireRole(projectId, principal.id(), ProjectMember.ROLE_VIEWER);
        requireRequirement(projectId, requirementId);
        return versionMapper.selectList(new LambdaQueryWrapper<RequirementVersion>()
                        .eq(RequirementVersion::getRequirementId, requirementId)
                        .orderByAsc(RequirementVersion::getVersion))
                .stream()
                .map(v -> new VersionView(v.getVersion(), v.getBody(), v.getSourceType(),
                        v.getAcceptanceCriteria(), createdByLabel(v.getCreatedBy()),
                        v.getCreatedAt() == null ? null : v.getCreatedAt().toString()))
                .toList();
    }

    @GetMapping("/{requirementId}/versions/{version}")
    public ResponseEntity<?> versionDetail(@PathVariable Long projectId, @PathVariable Long requirementId,
                                           @PathVariable int version, Authentication authentication) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        access.requireRole(projectId, principal.id(), ProjectMember.ROLE_VIEWER);
        requireRequirement(projectId, requirementId);
        RequirementVersion snapshot = versionMapper.selectOne(new LambdaQueryWrapper<RequirementVersion>()
                .eq(RequirementVersion::getRequirementId, requirementId)
                .eq(RequirementVersion::getVersion, version));
        if (snapshot == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(new VersionView(snapshot.getVersion(), snapshot.getBody(),
                snapshot.getSourceType(), snapshot.getAcceptanceCriteria(),
                createdByLabel(snapshot.getCreatedBy()),
                snapshot.getCreatedAt() == null ? null : snapshot.getCreatedAt().toString()));
    }

    private Requirement requireRequirement(Long projectId, Long requirementId) {
        Requirement requirement = requirementMapper.selectById(requirementId);
        if (requirement == null || !requirement.getProjectId().equals(projectId)) {
            throw new ResourceNotFoundException("需求不存在");
        }
        return requirement;
    }

    private RequirementVersion currentOf(Requirement requirement) {
        RequirementVersion current = versionMapper.selectOne(new LambdaQueryWrapper<RequirementVersion>()
                .eq(RequirementVersion::getRequirementId, requirement.getId())
                .eq(RequirementVersion::getVersion, requirement.getCurrentVersion()));
        if (current == null) {
            throw new ResourceNotFoundException("需求版本不存在");
        }
        return current;
    }

    private void insertVersion(Long requirementId, int version, String body, String sourceType,
                               String acceptanceCriteria, Long userId) {
        RequirementVersion snapshot = new RequirementVersion();
        snapshot.setRequirementId(requirementId);
        snapshot.setVersion(version);
        snapshot.setBody(body);
        snapshot.setSourceType(sourceType);
        snapshot.setAcceptanceCriteria(acceptanceCriteria);
        snapshot.setCreatedBy(userId);
        versionMapper.insert(snapshot);
    }

    private RequirementView loadView(Requirement requirement) {
        RequirementVersion current = currentOf(requirement);
        return new RequirementView(requirement.getId(), requirement.getTitle(),
                requirement.getCurrentVersion(), current.getBody(), current.getSourceType(),
                current.getAcceptanceCriteria());
    }

    private String createdByLabel(Long userId) {
        return String.valueOf(userId);
    }

    private ResponseEntity<?> validateContent(String title, String bodyText, String sourceType,
                                              String acceptanceCriteria) {
        if (title == null || title.isBlank() || title.length() > 200) {
            return badRequest("需求标题不能为空且不超过 200 字");
        }
        if (bodyText == null || bodyText.isBlank()) {
            return badRequest("需求正文不能为空");
        }
        if (bodyText.length() > 100_000) {
            return badRequest("需求正文过长");
        }
        if (sourceType == null || !SOURCE_TYPES.contains(sourceType)) {
            return badRequest("来源必须是 PASTED_TEXT 或 MARKDOWN");
        }
        if (acceptanceCriteria == null || acceptanceCriteria.isBlank()) {
            return badRequest("验收标准不能为空");
        }
        return null;
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<IdentityDtos.ErrorResponse> notFound(ResourceNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new IdentityDtos.ErrorResponse(404, e.getMessage()));
    }

    @ExceptionHandler(InsufficientRoleException.class)
    public ResponseEntity<IdentityDtos.ErrorResponse> forbidden(InsufficientRoleException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new IdentityDtos.ErrorResponse(403, e.getMessage()));
    }

    private ResponseEntity<?> badRequest(String message) {
        return ResponseEntity.badRequest().body(new IdentityDtos.ErrorResponse(400, message));
    }
}
