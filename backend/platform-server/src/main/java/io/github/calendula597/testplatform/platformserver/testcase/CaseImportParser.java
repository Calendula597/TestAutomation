package io.github.calendula597.testplatform.platformserver.testcase;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Parses uploaded CSV/XLSX files into raw grid rows. Formulas and macros are
 * never executed: POI renders cells through DataFormatter (cached values),
 * and parsing never opens macros.
 */
public final class CaseImportParser {

    public static final int MAX_FILE_BYTES = 5 * 1024 * 1024;
    public static final int MAX_ROWS = 2000;
    public static final int MAX_CELL_LENGTH = 10_000;

    private CaseImportParser() {
    }

    public record ParsedFile(List<Map<Integer, String>> rows, int columnCount) {
    }

    public static ParsedFile parse(InputStream content, String filename) throws IOException {
        if (filename != null && filename.toLowerCase().endsWith(".xlsx")) {
            return parseExcel(content);
        }
        return parseCsv(content);
    }

    private static ParsedFile parseCsv(InputStream content) throws IOException {
        String text = new String(content.readAllBytes(), StandardCharsets.UTF_8);
        List<Map<Integer, String>> rows = new ArrayList<>();
        int columnCount = 0;
        CSVParser parser = CSVFormat.DEFAULT.builder()
                .setIgnoreEmptyLines(false)
                .build()
                .parse(new StringReader(text));
        boolean headerSkipped = false;
        for (CSVRecord record : parser) {
            if (rows.size() >= MAX_ROWS) {
                break;
            }
            if (!headerSkipped) {
                // Migrated files carry a header row; it is not a case.
                headerSkipped = true;
                continue;
            }
            Map<Integer, String> row = new java.util.LinkedHashMap<>();
            boolean anyValue = false;
            for (int i = 0; i < record.size(); i++) {
                String value = truncate(record.get(i));
                if (!value.isBlank()) {
                    anyValue = true;
                }
                row.put(i, value);
                columnCount = Math.max(columnCount, i + 1);
            }
            if (anyValue) {
                rows.add(row);
            }
        }
        return new ParsedFile(rows, columnCount);
    }

    private static ParsedFile parseExcel(InputStream content) throws IOException {
        DataFormatter formatter = new DataFormatter();
        try (XSSFWorkbook workbook = new XSSFWorkbook(content)) {
            Sheet sheet = workbook.getSheetAt(0);
            List<Map<Integer, String>> rows = new ArrayList<>();
            int columnCount = 0;
            boolean headerSkipped = false;
            for (Row row : sheet) {
                if (rows.size() >= MAX_ROWS) {
                    break;
                }
                if (!headerSkipped) {
                    // Migrated files carry a header row; it is not a case.
                    headerSkipped = true;
                    continue;
                }
                Map<Integer, String> values = new java.util.LinkedHashMap<>();
                boolean anyValue = false;
                for (Cell cell : row) {
                    // DataFormatter renders the cell as Excel displays it; a
                    // formula cell yields its cached text without evaluation.
                    String value = truncate(formatter.formatCellValue(cell).trim());
                    if (!value.isEmpty()) {
                        anyValue = true;
                    }
                    values.put(cell.getColumnIndex(), value);
                }
                if (anyValue) {
                    rows.add(values);
                    columnCount = Math.max(columnCount, values.size());
                }
            }
            return new ParsedFile(rows, columnCount);
        }
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() > MAX_CELL_LENGTH ? value.substring(0, MAX_CELL_LENGTH) : value;
    }
}
