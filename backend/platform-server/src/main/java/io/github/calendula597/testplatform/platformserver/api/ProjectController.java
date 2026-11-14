package io.github.calendula597.testplatform.platformserver.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.calendula597.testplatform.platformserver.identity.AuditService;
import io.github.calendula597.testplatform.platformserver.identity.User;
import io.github.calendula597.testplatform.platformserver.identity.UserMapper;
import io.github.calendula597.testplatform.platformserver.identity.UserPrincipal;
import io.github.calendula597.testplatform.platformserver.project.InsufficientRoleException;
import io.github.calendula597.testplatform.platformserver.project.Project;
import io.github.calendula597.testplatform.platformserver.project.ProjectAccessService;
import io.github.calendula597.testplatform.platformserver.project.ProjectMapper;
import io.github.calendula597.testplatform.platformserver.project.ProjectMember;
import io.github.calendula597.testplatform.platformserver.project.ProjectMemberMapper;
import io.github.calendula597.testplatform.platformserver.project.ResourceNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
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
import java.util.Set;

/**
 * Project lifecycle and membership management. Every endpoint re-verifies
 * membership and role server-side via {@link ProjectAccessService};
 * non-members receive 404 so project existence is never leaked.
 */
@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private static final Set<String> PROJECT_ROLES = Set.of(
            ProjectMember.ROLE_OWNER, ProjectMember.ROLE_MEMBER, ProjectMember.ROLE_VIEWER);

    private final ProjectAccessService access;
    private final ProjectMapper projectMapper;
    private final ProjectMemberMapper projectMemberMapper;
    private final UserMapper userMapper;
    private final AuditService auditService;

    public ProjectController(ProjectAccessService access, ProjectMapper projectMapper,
                             ProjectMemberMapper projectMemberMapper,
                             UserMapper userMapper, AuditService auditService) {
        this.access = access;
        this.projectMemberMapper = projectMemberMapper;
        this.projectMapper = projectMapper;
        this.userMapper = userMapper;
        this.auditService = auditService;
    }

    public record CreateRequest(String name, String description) {
    }

    public record AddMemberRequest(String username, String role) {
    }

    public record RoleRequest(String role) {
    }

    public record ProjectView(Long id, String name, String description, String status, String myRole) {
    }

    public record MemberView(Long userId, String username, String role, String displayName) {
    }

    @PostMapping
    @Transactional
    public ResponseEntity<?> create(@RequestBody CreateRequest body, Authentication authentication) {
        if (body.name() == null || body.name().isBlank() || body.name().length() > 100) {
            return badRequest("项目名称不能为空且不超过 100 字");
        }
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();

        Project project = new Project();
        project.setName(body.name().trim());
        project.setDescription(body.description());
        project.setStatus(Project.STATUS_ACTIVE);
        project.setCreatedBy(principal.id());
        projectMapper.insert(project);

        ProjectMember member = new ProjectMember();
        member.setProjectId(project.getId());
        member.setUserId(principal.id());
        member.setRole(ProjectMember.ROLE_OWNER);
        projectMemberMapper.insert(member);

        auditService.record(authentication, "PROJECT_CREATED", null,
                Map.of("projectId", project.getId(), "name", project.getName()));
        return ResponseEntity.status(HttpStatus.CREATED).body(toView(project, ProjectMember.ROLE_OWNER));
    }

    @GetMapping
    public List<ProjectView> listMine(Authentication authentication) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        List<ProjectMember> memberships = projectMemberMapper.selectList(
                new LambdaQueryWrapper<ProjectMember>().eq(ProjectMember::getUserId, principal.id()));
        return memberships.stream()
                .map(m -> toView(access.requireProject(m.getProjectId()), m.getRole()))
                .toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> detail(@PathVariable Long id, Authentication authentication) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        ProjectMember membership = access.requireRole(id, principal.id(), ProjectMember.ROLE_VIEWER);
        return ResponseEntity.ok(toView(access.requireProject(id), membership.getRole()));
    }

    @PutMapping("/{id}/archive")
    @Transactional
    public ResponseEntity<?> archive(@PathVariable Long id, Authentication authentication) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        access.requireRole(id, principal.id(), ProjectMember.ROLE_OWNER);
        Project project = access.requireProject(id);
        if (Project.STATUS_ARCHIVED.equals(project.getStatus())) {
            return conflict("项目已归档");
        }
        project.setStatus(Project.STATUS_ARCHIVED);
        projectMapper.updateById(project);
        auditService.record(authentication, "PROJECT_ARCHIVED", null,
                Map.of("projectId", project.getId(), "name", project.getName()));
        return ResponseEntity.ok(toView(project, ProjectMember.ROLE_OWNER));
    }

    @GetMapping("/{id}/members")
    public List<MemberView> members(@PathVariable Long id, Authentication authentication) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        access.requireRole(id, principal.id(), ProjectMember.ROLE_VIEWER);

        return projectMemberMapper.selectList(
                        new LambdaQueryWrapper<ProjectMember>().eq(ProjectMember::getProjectId, id))
                .stream()
                .map(m -> {
                    User user = userMapper.selectById(m.getUserId());
                    String username = user == null ? "?" : user.getUsername();
                    String displayName = user == null ? null : user.getDisplayName();
                    return new MemberView(m.getUserId(), username, m.getRole(), displayName);
                })
                .toList();
    }

    @PostMapping("/{id}/members")
    @Transactional
    public ResponseEntity<?> addMember(@PathVariable Long id, @RequestBody AddMemberRequest body,
                                       Authentication authentication) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        requireActiveProject(id, principal.id(), ProjectMember.ROLE_OWNER);

        if (body.role() == null || !PROJECT_ROLES.contains(body.role())) {
            return badRequest("项目角色必须是 OWNER、MEMBER 或 VIEWER");
        }
        User user = body.username() == null ? null : userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, body.username()));
        if (user == null) {
            return badRequest("用户不存在");
        }
        Long existing = projectMemberMapper.selectCount(new LambdaQueryWrapper<ProjectMember>()
                .eq(ProjectMember::getProjectId, id)
                .eq(ProjectMember::getUserId, user.getId()));
        if (existing > 0) {
            return conflict("该用户已是项目成员");
        }

        ProjectMember member = new ProjectMember();
        member.setProjectId(id);
        member.setUserId(user.getId());
        member.setRole(body.role());
        projectMemberMapper.insert(member);

        auditService.record(authentication, "MEMBER_ADDED", user,
                Map.of("projectId", id, "role", body.role()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new MemberView(user.getId(), user.getUsername(), member.getRole(), user.getDisplayName()));
    }

    @PutMapping("/{id}/members/{userId}/role")
    @Transactional
    public ResponseEntity<?> changeRole(@PathVariable Long id, @PathVariable Long userId,
                                        @RequestBody RoleRequest body, Authentication authentication) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        ProjectMember acting = requireActiveProject(id, principal.id(), ProjectMember.ROLE_OWNER);

        if (body.role() == null || !PROJECT_ROLES.contains(body.role())) {
            return badRequest("项目角色必须是 OWNER、MEMBER 或 VIEWER");
        }
        ProjectMember member = projectMemberMapper.selectOne(new LambdaQueryWrapper<ProjectMember>()
                .eq(ProjectMember::getProjectId, id)
                .eq(ProjectMember::getUserId, userId));
        if (member == null) {
            return ResponseEntity.notFound().build();
        }
        String previousRole = member.getRole();
        if (!previousRole.equals(body.role())) {
            // Never leave the project without an OWNER.
            if (ProjectMember.ROLE_OWNER.equals(previousRole)) {
                Long owners = projectMemberMapper.selectCount(new LambdaQueryWrapper<ProjectMember>()
                        .eq(ProjectMember::getProjectId, id)
                        .eq(ProjectMember::getRole, ProjectMember.ROLE_OWNER));
                if (owners <= 1) {
                    return conflict("项目至少需要一名项目负责人");
                }
            }
            member.setRole(body.role());
            projectMemberMapper.updateById(member);
            User target = userMapper.selectById(userId);
            auditService.record(authentication, "MEMBER_ROLE_CHANGED", target,
                    Map.of("projectId", id, "previousRole", previousRole, "newRole", body.role()));
        }
        User user = userMapper.selectById(userId);
        return ResponseEntity.ok(new MemberView(userId, user == null ? "?" : user.getUsername(),
                member.getRole(), user == null ? null : user.getDisplayName()));
    }

    @DeleteMapping("/{id}/members/{userId}")
    @Transactional
    public ResponseEntity<?> removeMember(@PathVariable Long id, @PathVariable Long userId,
                                          Authentication authentication) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        requireActiveProject(id, principal.id(), ProjectMember.ROLE_OWNER);

        ProjectMember member = projectMemberMapper.selectOne(new LambdaQueryWrapper<ProjectMember>()
                .eq(ProjectMember::getProjectId, id)
                .eq(ProjectMember::getUserId, userId));
        if (member == null) {
            return ResponseEntity.notFound().build();
        }
        if (ProjectMember.ROLE_OWNER.equals(member.getRole())) {
            Long owners = projectMemberMapper.selectCount(new LambdaQueryWrapper<ProjectMember>()
                    .eq(ProjectMember::getProjectId, id)
                    .eq(ProjectMember::getRole, ProjectMember.ROLE_OWNER));
            if (owners <= 1) {
                return conflict("项目至少需要一名项目负责人");
            }
        }
        projectMemberMapper.deleteById(member.getId());
        User target = userMapper.selectById(userId);
        auditService.record(authentication, "MEMBER_REMOVED", target, Map.of("projectId", id));
        return ResponseEntity.noContent().build();
    }

    /** Member check + archived guard for mutating project operations. */
    private ProjectMember requireActiveProject(Long projectId, Long userId, String minimumRole) {
        ProjectMember member = access.requireRole(projectId, userId, minimumRole);
        if (Project.STATUS_ARCHIVED.equals(access.requireProject(projectId).getStatus())) {
            throw new IllegalStateException("项目已归档，不能修改");
        }
        return member;
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

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<IdentityDtos.ErrorResponse> conflictFromState(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new IdentityDtos.ErrorResponse(409, e.getMessage()));
    }

    private ResponseEntity<?> badRequest(String message) {
        return ResponseEntity.badRequest().body(new IdentityDtos.ErrorResponse(400, message));
    }

    private ResponseEntity<?> conflict(String message) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new IdentityDtos.ErrorResponse(409, message));
    }

    private ProjectView toView(Project project, String role) {
        return new ProjectView(project.getId(), project.getName(), project.getDescription(),
                project.getStatus(), role);
    }
}
