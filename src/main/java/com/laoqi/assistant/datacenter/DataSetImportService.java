package com.laoqi.assistant.datacenter;

import com.laoqi.assistant.datacenter.model.DataField;
import com.laoqi.assistant.datacenter.model.DataSchema;
import com.laoqi.assistant.datacenter.model.DataSet;
import org.apache.poi.ss.usermodel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class DataSetImportService {

    private static final Logger log = LoggerFactory.getLogger(DataSetImportService.class);

    public static class ExcelPreview {
        private List<String> headers;
        private List<List<String>> rows;
        private int totalRows;

        public List<String> getHeaders() { return headers; }
        public void setHeaders(List<String> headers) { this.headers = headers; }
        public List<List<String>> getRows() { return rows; }
        public void setRows(List<List<String>> rows) { this.rows = rows; }
        public int getTotalRows() { return totalRows; }
        public void setTotalRows(int totalRows) { this.totalRows = totalRows; }
    }

    public ExcelPreview previewExcel(MultipartFile file) throws Exception {
        ExcelPreview preview = new ExcelPreview();
        preview.setHeaders(new ArrayList<>());
        preview.setRows(new ArrayList<>());

        try (InputStream is = file.getInputStream();
             Workbook workbook = WorkbookFactory.create(is)) {

            Sheet sheet = workbook.getSheetAt(0);
            if (sheet == null || sheet.getPhysicalNumberOfRows() == 0) {
                return preview;
            }

            int headerRowIndex = findHeaderRow(sheet);
            if (headerRowIndex < 0) return preview;

            Row headerRow = sheet.getRow(headerRowIndex);
            if (headerRow != null) {
                for (int i = 0; i < headerRow.getPhysicalNumberOfCells(); i++) {
                    Cell cell = headerRow.getCell(i);
                    preview.getHeaders().add(getCellValue(cell));
                }
            }

            int maxPreviewRows = Math.min(sheet.getPhysicalNumberOfRows(), headerRowIndex + 51);
            for (int i = headerRowIndex + 1; i < maxPreviewRows; i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;
                List<String> rowData = new ArrayList<>();
                for (int j = 0; j < preview.getHeaders().size(); j++) {
                    Cell cell = row.getCell(j);
                    rowData.add(getCellValue(cell));
                }
                preview.getRows().add(rowData);
            }

            preview.setTotalRows(sheet.getPhysicalNumberOfRows() - headerRowIndex - 1);
        }

        return preview;
    }

    private int findHeaderRow(Sheet sheet) {
        for (int i = 0; i < Math.min(10, sheet.getPhysicalNumberOfRows()); i++) {
            Row row = sheet.getRow(i);
            if (row == null) continue;
            List<String> values = new ArrayList<>();
            for (int j = 0; j < row.getPhysicalNumberOfCells(); j++) {
                Cell cell = row.getCell(j);
                String val = getCellValue(cell);
                if (!val.isEmpty()) values.add(val);
            }
            if (values.size() < 2) continue;
            Set<String> unique = new HashSet<>(values);
            if (unique.size() < 2) continue;
            boolean hasLongValue = values.stream().anyMatch(v -> v.length() > 20);
            if (!hasLongValue) return i;
        }
        return 0;
    }

    public List<Map<String, Object>> importExcel(MultipartFile file, Map<String, String> columnMapping) throws Exception {
        List<Map<String, Object>> records = new ArrayList<>();

        try (InputStream is = file.getInputStream();
             Workbook workbook = WorkbookFactory.create(is)) {

            Sheet sheet = workbook.getSheetAt(0);
            if (sheet == null || sheet.getPhysicalNumberOfRows() < 2) return records;

            int headerRowIndex = findHeaderRow(sheet);
            Row headerRow = sheet.getRow(headerRowIndex);
            List<String> headers = new ArrayList<>();
            for (int i = 0; i < headerRow.getPhysicalNumberOfCells(); i++) {
                headers.add(getCellValue(headerRow.getCell(i)));
            }

            for (int i = headerRowIndex + 1; i < sheet.getPhysicalNumberOfRows(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                Map<String, Object> record = new LinkedHashMap<>();
                boolean hasData = false;

                for (int j = 0; j < headers.size(); j++) {
                    String excelCol = headers.get(j);
                    String fieldName = columnMapping.getOrDefault(excelCol, excelCol);
                    Cell cell = row.getCell(j);
                    String value = getCellValue(cell);

                    if (value != null && !value.isEmpty()) {
                        hasData = true;
                    }

                    record.put(fieldName, value != null ? value : "");
                }

                if (hasData) {
                    records.add(record);
                }
            }
        }

        log.info("Imported {} records from Excel", records.size());
        return records;
    }

    public List<Map<String, Object>> importExcelWithAutoDetect(MultipartFile file, DataSchema schema) throws Exception {
        Map<String, String> autoMapping = new HashMap<>();

        try (InputStream is = file.getInputStream();
             Workbook workbook = WorkbookFactory.create(is)) {

            Sheet sheet = workbook.getSheetAt(0);
            if (sheet == null || sheet.getPhysicalNumberOfRows() < 2) return new ArrayList<>();

            int headerRowIndex = findHeaderRow(sheet);
            Row headerRow = sheet.getRow(headerRowIndex);
            List<String> headers = new ArrayList<>();
            for (int i = 0; i < headerRow.getPhysicalNumberOfCells(); i++) {
                headers.add(getCellValue(headerRow.getCell(i)));
            }

            if (schema != null && schema.getFields() != null) {
                for (DataField field : schema.getFields()) {
                    String fieldLower = field.getName().toLowerCase();
                    String displayLower = field.getDisplayName() != null ? field.getDisplayName().toLowerCase() : "";

                    for (String header : headers) {
                        String headerLower = header.toLowerCase();
                        if (headerLower.equals(fieldLower) || headerLower.equals(displayLower)
                                || headerLower.contains(fieldLower) || fieldLower.contains(headerLower)
                                || (displayLower.isEmpty() ? false : headerLower.contains(displayLower) || displayLower.contains(headerLower))) {
                            autoMapping.put(header, field.getName());
                            break;
                        }
                    }
                }
            }

            for (String header : headers) {
                if (!autoMapping.containsKey(header)) {
                    autoMapping.put(header, header);
                }
            }
        }

        return importExcel(file, autoMapping);
    }

    private String getCellValue(Cell cell) {
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                double val = cell.getNumericCellValue();
                if (val == Math.floor(val) && !Double.isInfinite(val)) {
                    yield String.valueOf((long) val);
                }
                yield String.valueOf(val);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> {
                try {
                    yield cell.getStringCellValue();
                } catch (Exception e) {
                    try {
                        yield String.valueOf(cell.getNumericCellValue());
                    } catch (Exception e2) {
                        yield "";
                    }
                }
            }
            default -> "";
        };
    }
}
