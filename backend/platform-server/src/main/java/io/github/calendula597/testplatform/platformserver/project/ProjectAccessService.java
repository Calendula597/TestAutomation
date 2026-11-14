package io.github.calendula597.testplatform.platformserver.project;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

/**
 * Server-side project authorization. Every project-scoped endpoint resolves
 * membership here — the frontend hiding controls is never trusted.
 *
 * Non-members get 404 so the platform does not leak project existence;
 * members without sufficient role get 403.
 */
@Service
public class ProjectAccessService {

    private final ProjectMapper projectMapper;
    private final ProjectMemberMapper projectMemberMapper;

    public ProjectAccessService(ProjectMapper projectMapper, ProjectMemberMapper projectMemberMapper) {
        this.projectMapper = projectMapper;
        this.projectMemberMapper = projectMemberMapper;
    }

    public Project requireProject(Long projectId) {
        Project project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new ResourceNotFoundException("项目不存在");
        }
        return project;
    }

    public ProjectMember requireMembership(Long projectId, Long userId) {
        requireProject(projectId);
        ProjectMember member = projectMemberMapper.selectOne(new LambdaQueryWrapper<ProjectMember>()
                .eq(ProjectMember::getProjectId, projectId)
                .eq(ProjectMember::getUserId, userId));
        if (member == null) {
            throw new ResourceNotFoundException("项目不存在");
        }
        return member;
    }

    /** VIEWER < MEMBER < OWNER. */
    public ProjectMember requireRole(Long projectId, Long userId, String minimumRole) {
        ProjectMember member = requireMembership(projectId, userId);
        if (roleLevel(member.getRole()) < roleLevel(minimumRole)) {
            throw new InsufficientRoleException("当前项目角色权限不足");
        }
        return member;
    }

    private static int roleLevel(String role) {
        return switch (role) {
            case ProjectMember.ROLE_VIEWER -> 1;
            case ProjectMember.ROLE_MEMBER -> 2;
            case ProjectMember.ROLE_OWNER -> 3;
            default -> 0;
        };
    }
}
