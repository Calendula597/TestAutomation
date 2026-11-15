package io.github.calendula597.testplatform.platformserver.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.calendula597.testplatform.platformserver.identity.UserPrincipal;
import io.github.calendula597.testplatform.platformserver.project.InsufficientRoleException;
import io.github.calendula597.testplatform.platformserver.project.ProjectAccessService;
import io.github.calendula597.testplatform.platformserver.project.ProjectMember;
import io.github.calendula597.testplatform.platformserver.project.ResourceNotFoundException;
import io.github.calendula597.testplatform.platformserver.reporting.EvidenceStorage;
import io.github.calendula597.testplatform.platformserver.reporting.ReportImport;
import io.github.calendula597.testplatform.platformserver.reporting.ReportImportMapper;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class ReportImportController {

    private final ProjectAccessService access;
    private final ReportImportMapper importMapper;
    private final EvidenceStorage evidenceStorage;

    public ReportImportController(ProjectAccessService access, ReportImportMapper importMapper,
                                  EvidenceStorage evidenceStorage) {
        this.access = access;
        this.importMapper = importMapper;
        this.evidenceStorage = evidenceStorage;
    }

    public record ImportRequest(String language, String filename, String content) {
    }

    @PostMapping("/api/v1/projects/{projectId}/report-imports")
    public ResponseEntity<?> create(@PathVariable Long projectId, @RequestBody ImportRequest body,
                                    Authentication authentication) throws IOException {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        access.requireRole(projectId, principal.id(), ProjectMember.ROLE_MEMBER);
        if (body.language() == null || !body.language().matches("[A-Za-z][A-Za-z0-9_+-]{0,39}")) {
            return badRequest("报告语言格式无效");
        }
        if (body.filename() == null || body.filename().isBlank() || body.filename().length() > 120
                || body.filename().contains("/") || body.filename().contains("\\")) {
            return badRequest("报告文件名无效");
        }
        if (body.content() == null || body.content().isBlank() || body.content().length() > 2_000_000) {
            return badRequest("报告内容不能为空且不能超过 200 万字符");
        }
        Path runDirectory = evidenceStorage.createRunDirectory();
        Path report = runDirectory.resolve(body.filename());
        Files.writeString(report, body.content());
        ReportImport row = new ReportImport();
        row.setProjectId(projectId);
        row.setLanguage(body.language().toUpperCase());
        row.setOriginalFilename(body.filename());
        row.setStorageKey(evidenceStorage.keyOf(report));
        row.setCreatedBy(principal.id());
        importMapper.insert(row);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", row.getId(), "status", "STORED"));
    }

    @GetMapping("/api/v1/projects/{projectId}/report-imports")
    public List<Map<String, Object>> list(@PathVariable Long projectId, Authentication authentication) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        access.requireRole(projectId, principal.id(), ProjectMember.ROLE_VIEWER);
        return importMapper.selectList(new LambdaQueryWrapper<ReportImport>()
                        .eq(ReportImport::getProjectId, projectId).orderByAsc(ReportImport::getId))
                .stream().map(ReportImportController::view).toList();
    }

    @GetMapping("/api/v1/projects/{projectId}/report-imports/{id}/content")
    public ResponseEntity<Resource> content(@PathVariable Long projectId, @PathVariable Long id,
                                            Authentication authentication) throws IOException {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        access.requireRole(projectId, principal.id(), ProjectMember.ROLE_VIEWER);
        ReportImport row = importMapper.selectById(id);
        if (row == null || !projectId.equals(row.getProjectId())) {
            throw new ResourceNotFoundException("导入报告不存在");
        }
        Resource resource = evidenceStorage.read(row.getStorageKey());
        return ResponseEntity.ok()
                .header("Content-Type", "application/octet-stream")
                .header("Content-Disposition", "attachment; filename=\"" + row.getOriginalFilename() + "\"")
                .body(resource);
    }

    private static Map<String, Object> view(ReportImport row) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", row.getId());
        view.put("language", row.getLanguage());
        view.put("filename", row.getOriginalFilename());
        view.put("createdAt", row.getCreatedAt() == null ? null : row.getCreatedAt().toString());
        return view;
    }

    private ResponseEntity<?> badRequest(String message) {
        return ResponseEntity.badRequest().body(new IdentityDtos.ErrorResponse(400, message));
    }

    @ExceptionHandler({ResourceNotFoundException.class, InsufficientRoleException.class})
    public ResponseEntity<?> mapped(RuntimeException e) {
        HttpStatus status = e instanceof ResourceNotFoundException ? HttpStatus.NOT_FOUND : HttpStatus.FORBIDDEN;
        return ResponseEntity.status(status).body(new IdentityDtos.ErrorResponse(status.value(), e.getMessage()));
    }
}
