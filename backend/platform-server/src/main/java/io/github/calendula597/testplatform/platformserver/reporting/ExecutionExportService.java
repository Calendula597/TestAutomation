package io.github.calendula597.testplatform.platformserver.reporting;

import io.github.calendula597.testplatform.platformserver.worker.ExecutionAttempt;
import io.github.calendula597.testplatform.platformserver.worker.ExecutionJob;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Service
public class ExecutionExportService {

    private final ObjectMapper objectMapper;

    public ExecutionExportService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public record ExportPayload(String filename, String contentType, byte[] content) {
    }

    public ExportPayload export(ExecutionJob job, List<Map<String, Object>> attempts, String format) throws Exception {
        return switch (format.toLowerCase()) {
            case "html" -> new ExportPayload("execution-" + job.getId() + ".html", "text/html",
                    html(job, attempts).getBytes(StandardCharsets.UTF_8));
            case "junit" -> new ExportPayload("execution-" + job.getId() + "-junit.xml", "application/xml",
                    junit(job, attempts).getBytes(StandardCharsets.UTF_8));
            case "csv" -> new ExportPayload("execution-" + job.getId() + ".csv", "text/csv",
                    csv(job, attempts).getBytes(StandardCharsets.UTF_8));
            case "xlsx" -> new ExportPayload("execution-" + job.getId() + ".xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsx(job, attempts));
            case "raw" -> new ExportPayload("execution-" + job.getId() + "-attempts.json", "application/json",
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(attempts)
                            .getBytes(StandardCharsets.UTF_8));
            default -> throw new IllegalArgumentException("导出格式必须是 html、junit、csv、xlsx 或 raw");
        };
    }

    private String html(ExecutionJob job, List<Map<String, Object>> attempts) {
        StringBuilder html = new StringBuilder("<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\">")
                .append("<title>执行报告 #").append(job.getId()).append("</title>")
                .append("<h1>执行报告 #").append(job.getId()).append("</h1>")
                .append("<p>类型：").append(escape(job.getJobType())).append("；状态：")
                .append(escape(job.getStatus())).append("</p>");
        for (Map<String, Object> attempt : attempts) {
            html.append("<section><h2>尝试 #").append(attempt.get("attemptNumber"))
                    .append("：").append(escape(String.valueOf(attempt.get("status"))))
                    .append("</h2><pre>")
                    .append(escape(objectMapper.writerWithDefaultPrettyPrinter()
                            .writeValueAsString(attempt.get("result"))))
                    .append("</pre></section>");
        }
        return html.append("</html>").toString();
    }

    private String junit(ExecutionJob job, List<Map<String, Object>> attempts) {
        int failures = (int) attempts.stream().filter(attempt ->
                "ASSERTION_FAILED".equals(attempt.get("status"))).count();
        int errors = (int) attempts.stream().filter(attempt ->
                "ERROR".equals(attempt.get("status")) || "TIMED_OUT".equals(attempt.get("status"))).count();
        int skipped = (int) attempts.stream().filter(attempt ->
                "CANCELLED".equals(attempt.get("status"))).count();
        double duration = attempts.stream().mapToDouble(attempt ->
                ((Number) attempt.getOrDefault("durationMs", 0)).doubleValue()).sum() / 1000;
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .append("<testsuite name=\"execution-").append(job.getId())
                .append("\" tests=\"").append(attempts.size())
                .append("\" failures=\"").append(failures)
                .append("\" errors=\"").append(errors)
                .append("\" skipped=\"").append(skipped).append("\" time=\"").append(duration).append("\">");
        for (Map<String, Object> attempt : attempts) {
            double durationMs = ((Number) attempt.getOrDefault("durationMs", 0)).doubleValue();
            xml.append("<testcase name=\"attempt-").append(attempt.get("attemptNumber"))
                    .append("\" time=\"").append(durationMs / 1000).append("\">");
            if ("ASSERTION_FAILED".equals(attempt.get("status"))) {
                xml.append("<failure message=\"断言失败\"/>");
            } else if ("ERROR".equals(attempt.get("status"))) {
                xml.append("<error message=\"执行错误\"/>");
            } else if ("TIMED_OUT".equals(attempt.get("status"))) {
                xml.append("<error message=\"执行超时\"/>");
            } else if ("CANCELLED".equals(attempt.get("status"))) {
                xml.append("<skipped message=\"执行已取消\"/>");
            }
            xml.append("</testcase>");
        }
        return xml.append("</testsuite>").toString();
    }

    private String csv(ExecutionJob job, List<Map<String, Object>> attempts) {
        StringBuilder csv = new StringBuilder("jobId,attemptNumber,status,durationMs,resultJson\n");
        for (Map<String, Object> attempt : attempts) {
            csv.append(job.getId()).append(',')
                    .append(attempt.get("attemptNumber")).append(',')
                    .append(attempt.get("status")).append(',')
                    .append(attempt.get("durationMs")).append(',')
                    .append('"')
                    .append(objectMapper.writeValueAsString(attempt.get("result")).replace("\"", "\"\""))
                    .append('"').append('\n');
        }
        return csv.toString();
    }

    private byte[] xlsx(ExecutionJob job, List<Map<String, Object>> attempts) throws Exception {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("执行报告");
            Row header = sheet.createRow(0);
            for (int i = 0; i < 5; i++) {
                header.createCell(i).setCellValue(List.of("jobId", "attemptNumber", "status", "durationMs", "resultJson").get(i));
            }
            int index = 1;
            for (Map<String, Object> attempt : attempts) {
                Row row = sheet.createRow(index++);
                row.createCell(0).setCellValue(job.getId());
                row.createCell(1).setCellValue(((Number) attempt.get("attemptNumber")).intValue());
                row.createCell(2).setCellValue(String.valueOf(attempt.get("status")));
                row.createCell(3).setCellValue(((Number) attempt.get("durationMs")).longValue());
                row.createCell(4).setCellValue(objectMapper.writeValueAsString(attempt.get("result")));
            }
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }
}
