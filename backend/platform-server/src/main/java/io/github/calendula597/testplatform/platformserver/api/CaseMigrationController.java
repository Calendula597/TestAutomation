package io.github.calendula597.testplatform.platformserver.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.calendula597.testplatform.platformserver.identity.AuditService;
import io.github.calendula597.testplatform.platformserver.identity.UserPrincipal;
import io.github.calendula597.testplatform.platformserver.project.InsufficientRoleException;
import io.github.calendula597.testplatform.platformserver.project.ProjectAccessService;
import io.github.calendula597.testplatform.platformserver.project.ProjectMember;
import io.github.calendula597.testplatform.platformserver.project.ResourceNotFoundException;
import io.github.calendula597.testplatform.platformserver.testcase.CaseImportParser;
import io.github.calendula597.testplatform.platformserver.testcase.CellSanitizer;
import io.github.calendula597.testplatform.platformserver.testcase.TestCase;
import io.github.calendula597.testplatform.platformserver.testcase.TestCaseMapper;
import io.github.calendula597.testplatform.platformserver.testcase.TestCaseStep;
import io.github.calendula597.testplatform.platformserver.testcase.TestCaseStepMapper;
import io.github.calendula597.testplatform.platformserver.testcase.TestCaseVersion;
import io.github.calendula597.testplatform.platformserver.testcase.TestCaseVersionMapper;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Excel/CSV migration: preview with row-level errors before saving drafts,
 * strict size limits, and authorized exports with injection-safe cells.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/testcases")
public class CaseMigrationController {

    private static final Set<String> PRIORITIES = java.util.Set.of(
            TestCase.PRIORITY_HIGH, TestCase.PRIORITY_MEDIUM, TestCase.PRIORITY_LOW);

    private final ProjectAccessService access;
    private final TestCaseMapper testCaseMapper;
    private final TestCaseStepMapper stepMapper;
    private final TestCaseVersionMapper versionMapper;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;

    public CaseMigrationController(ProjectAccessService access, TestCaseMapper testCaseMapper,
                                   TestCaseStepMapper stepMapper, TestCaseVersionMapper versionMapper,
                                   ObjectMapper objectMapper, AuditService auditService) {
        this.access = access;
        this.testCaseMapper = testCaseMapper;
        this.stepMapper = stepMapper;
        this.versionMapper = versionMapper;
        this.objectMapper = objectMapper;
        this.auditService = auditService;
    }

    public record Mapping(int titleColumn, Integer priorityColumn, Integer stepsColumn) {
    }

    public record PreviewRow(int rowNumber, String title, String priority, List<TestCaseController.StepInput> steps,
                             List<String> errors) {
    }

    public record PreviewResponse(int totalRows, List<PreviewRow> rows, int columnCount) {
    }

    public record CommitRow(String title, String priority, List<TestCaseController.StepInput> steps) {
    }

    public record CommitRequest(List<CommitRow> rows) {
    }

    @PostMapping(value = "/import/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> preview(@PathVariable Long projectId,
                                     @RequestParam("file") MultipartFile file,
                                     @RequestParam("mapping") String mappingJson,
                                     Authentication authentication) throws IOException {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        access.requireRole(projectId, principal.id(), ProjectMember.ROLE_MEMBER);

        if (file.getSize() > CaseImportParser.MAX_FILE_BYTES) {
            return badRequest("文件大小超过 5MB 限制");
        }
        Mapping mapping;
        try {
            mapping = objectMapper.readValue(mappingJson, Mapping.class);
        } catch (Exception e) {
            return badRequest("映射配置无法解析");
        }

        CaseImportParser.ParsedFile parsed;
        try {
            parsed = CaseImportParser.parse(file.getInputStream(), file.getOriginalFilename());
        } catch (IOException | RuntimeException e) {
            return badRequest("文件解析失败，请确认是 UTF-8 CSV 或 .xlsx 文件");
        }

        List<PreviewRow> rows = new ArrayList<>();
        int valid = 0;
        int rowNumber = 0;
        for (Map<Integer, String> cells : parsed.rows()) {
            rowNumber++;
            List<String> errors = new ArrayList<>();
            String title = cellAt(cells, mapping.titleColumn());
            String priority = cellAt(cells, mapping.priorityColumn());
            List<TestCaseController.StepInput> steps = new ArrayList<>();
            if (mapping.stepsColumn() != null) {
                String stepsText = cellAt(cells, mapping.stepsColumn());
                steps = parseStepsText(stepsText, errors);
            }
            if (title == null || title.isBlank()) {
                errors.add("标题为空");
            } else if (title.length() > 200) {
                errors.add("标题超过 200 字");
            }
            if (priority != null && !priority.isBlank() && !PRIORITIES.contains(priority)) {
                errors.add("优先级取值不合法：" + priority);
            } else if (priority == null || priority.isBlank()) {
                priority = TestCase.PRIORITY_MEDIUM;
            }
            if (steps.isEmpty()) {
                errors.add("未解析到任何步骤");
            }
            PreviewRow row = new PreviewRow(rowNumber, title, priority, steps, errors);
            if (errors.isEmpty()) {
                valid++;
            }
            rows.add(row);
        }
        auditService.record(authentication, "TESTCASE_IMPORT_PREVIEWED", null,
                Map.of("projectId", projectId, "totalRows", rowNumber, "valid", valid));
        return ResponseEntity.ok(new PreviewResponse(rowNumber, rows, parsed.columnCount()));
    }

    @PostMapping("/import/commit")
    @Transactional
    public ResponseEntity<?> commit(@PathVariable Long projectId, @RequestBody CommitRequest body,
                                    Authentication authentication) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        access.requireRole(projectId, principal.id(), ProjectMember.ROLE_MEMBER);

        if (body.rows() == null || body.rows().isEmpty()) {
            return badRequest("没有要导入的行");
        }
        if (body.rows().size() > CaseImportParser.MAX_ROWS) {
            return badRequest("一次最多导入 " + CaseImportParser.MAX_ROWS + " 行");
        }

        List<Map<String, Object>> results = new ArrayList<>();
        int created = 0;
        int rowNumber = 0;
        for (CommitRow row : body.rows()) {
            rowNumber++;
            List<String> errors = new ArrayList<>();
            if (row.title() == null || row.title().isBlank() || row.title().length() > 200) {
                errors.add("标题为空或超过 200 字");
            }
            if (row.priority() != null && !PRIORITIES.contains(row.priority())) {
                errors.add("优先级取值不合法");
            }
            if (row.steps() == null || row.steps().isEmpty()) {
                errors.add("未解析到任何步骤");
            }
            if (!errors.isEmpty()) {
                results.add(Map.of("rowNumber", rowNumber, "status", 400, "errors", errors));
                continue;
            }

            TestCase testCase = new TestCase();
            testCase.setProjectId(projectId);
            testCase.setTitle(row.title().trim());
            testCase.setPriority(row.priority() == null ? TestCase.PRIORITY_MEDIUM : row.priority());
            testCase.setStatus(TestCase.STATUS_DRAFT);
            testCase.setVersion(1);
            testCase.setCreatedBy(principal.id());
            testCaseMapper.insert(testCase);
            int order = 1;
            for (TestCaseController.StepInput step : row.steps()) {
                TestCaseStep row2 = new TestCaseStep();
                row2.setCaseId(testCase.getId());
                row2.setStepOrder(order++);
                row2.setAction(step.action());
                row2.setTestData(step.testData());
                row2.setExpected(step.expected() == null ? "" : step.expected());
                stepMapper.insert(row2);
            }
            created++;
            results.add(Map.of("rowNumber", rowNumber, "status", 201, "caseId", testCase.getId()));
        }

        auditService.record(authentication, "TESTCASE_IMPORTED", null,
                Map.of("projectId", projectId, "created", created, "failed", results.size() - created));
        return ResponseEntity.ok(Map.of("created", created, "results", results));
    }

    @GetMapping("/export")
    public ResponseEntity<?> export(@PathVariable Long projectId,
                                    @RequestParam("format") String format,
                                    Authentication authentication) throws IOException {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        access.requireRole(projectId, principal.id(), ProjectMember.ROLE_VIEWER);

        List<TestCase> cases = testCaseMapper.selectList(new LambdaQueryWrapper<TestCase>()
                .eq(TestCase::getProjectId, projectId)
                .orderByAsc(TestCase::getId));
        List<ExportedCase> exported = cases.stream().map(this::exportedCase).toList();

        String filename = "testcases-" + projectId;
        byte[] content;
        if ("xlsx".equalsIgnoreCase(format)) {
            content = excel(exported);
            filename += ".xlsx";
        } else if ("csv".equalsIgnoreCase(format)) {
            content = csv(exported);
            filename += ".csv";
        } else {
            return badRequest("format 必须是 csv 或 xlsx");
        }

        auditService.record(authentication, "TESTCASE_EXPORTED", null,
                Map.of("projectId", projectId, "format", format, "count", exported.size()));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentLength(content.length)
                .contentType("xlsx".equalsIgnoreCase(format)
                        ? MediaType.APPLICATION_OCTET_STREAM
                        : MediaType.valueOf("text/csv;charset=utf-8"))
                .body(content);
    }

    private record ExportedCase(long id, String title, String priority, String status,
                                String stepsText, String basis) {
    }

    /** Export prefers the latest READY snapshot; falls back to the draft row. */
    private ExportedCase exportedCase(TestCase testCase) {
        TestCaseVersion ready = versionMapper.selectOne(new LambdaQueryWrapper<TestCaseVersion>()
                .eq(TestCaseVersion::getCaseId, testCase.getId())
                .orderByDesc(TestCaseVersion::getVersion)
                .last("LIMIT 1"));
        if (ready != null) {
            StringBuilder steps = new StringBuilder();
            try {
                List<CaseReviewController.DiffStep> parsed = objectMapper.readValue(ready.getStepsJson(),
                        objectMapper.getTypeFactory().constructCollectionType(List.class,
                                CaseReviewController.DiffStep.class));
                for (CaseReviewController.DiffStep s : parsed) {
                    steps.append(s.action()).append(" :: ").append(s.expected()).append("\n");
                }
            } catch (Exception e) {
                steps.append("(步骤反序列化失败)");
            }
            return new ExportedCase(testCase.getId(), ready.getTitle(), ready.getPriority(),
                    ready.getStatus(), steps.toString(),
                    ready.getPublishBasis() == null ? "" : ready.getPublishBasis());
        }
        List<TestCaseStep> steps = stepMapper.selectList(new LambdaQueryWrapper<TestCaseStep>()
                .eq(TestCaseStep::getCaseId, testCase.getId())
                .orderByAsc(TestCaseStep::getStepOrder));
        StringBuilder text = new StringBuilder();
        for (TestCaseStep s : steps) {
            text.append(s.getAction()).append(" :: ").append(s.getExpected() == null ? "" : s.getExpected())
                    .append("\n");
        }
        return new ExportedCase(testCase.getId(), testCase.getTitle(), testCase.getPriority(),
                testCase.getStatus(), text.toString(), "");
    }

    private byte[] csv(List<ExportedCase> cases) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (CSVPrinter printer = new CSVPrinter(
                new java.io.OutputStreamWriter(out, java.nio.charset.StandardCharsets.UTF_8),
                CSVFormat.DEFAULT.builder().setHeader("ID", "标题", "优先级", "状态", "操作步骤 :: 预期结果", "发布依据")
                        .build())) {
            for (ExportedCase c : cases) {
                printer.printRecord(c.id(), CellSanitizer.sanitize(c.title()),
                        c.priority(), CellSanitizer.sanitize(c.status()),
                        CellSanitizer.sanitize(c.stepsText()), CellSanitizer.sanitize(c.basis()));
            }
        }
        return out.toByteArray();
    }

    private byte[] excel(List<ExportedCase> cases) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("测试用例");
            Row header = sheet.createRow(0);
            String[] headers = {"ID", "标题", "优先级", "状态", "操作步骤 :: 预期结果", "发布依据"};
            for (int i = 0; i < headers.length; i++) {
                header.createCell(i).setCellValue(headers[i]);
            }
            int rowIndex = 1;
            for (ExportedCase c : cases) {
                Row row = sheet.createRow(rowIndex++);
                row.createCell(0).setCellValue(c.id());
                row.createCell(1).setCellValue(CellSanitizer.sanitize(c.title()));
                row.createCell(2).setCellValue(c.priority());
                row.createCell(3).setCellValue(CellSanitizer.sanitize(c.status()));
                row.createCell(4).setCellValue(CellSanitizer.sanitize(c.stepsText()));
                row.createCell(5).setCellValue(CellSanitizer.sanitize(c.basis()));
            }
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private String cellAt(Map<Integer, String> cells, Integer column) {
        if (column == null) {
            return null;
        }
        return cells.get(column);
    }

    private List<TestCaseController.StepInput> parseStepsText(String text, List<String> errors) {
        List<TestCaseController.StepInput> steps = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return steps;
        }
        if (text.length() > CaseImportParser.MAX_CELL_LENGTH) {
            errors.add("步骤文本超过 10KB 限制");
            return steps;
        }
        int order = 1;
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] parts = trimmed.split("::", 2);
            String action = parts[0].trim();
            String expected = parts.length > 1 ? parts[1].trim() : "";
            if (action.isEmpty() || expected.isEmpty()) {
                errors.add("第 " + order + " 步格式应为「动作 :: 预期」");
                return steps;
            }
            steps.add(new TestCaseController.StepInput(order++, action, "", expected));
        }
        return steps;
    }

    @ExceptionHandler({ResourceNotFoundException.class, InsufficientRoleException.class})
    public ResponseEntity<?> mapped(RuntimeException e) {
        if (e instanceof ResourceNotFoundException) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new IdentityDtos.ErrorResponse(404, e.getMessage()));
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new IdentityDtos.ErrorResponse(403, e.getMessage()));
    }

    private ResponseEntity<?> badRequest(String message) {
        return ResponseEntity.badRequest().body(new IdentityDtos.ErrorResponse(400, message));
    }
}
