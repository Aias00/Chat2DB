package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.service.db.IDbImportPreviewService;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.tools.wrapper.result.DataResult;
import ai.chat2db.community.web.api.aspect.connection.ConnectionInfoAspect;
import ai.chat2db.community.web.api.model.request.data.source.DataSourceBaseRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Bounded import preview and column mapping (MYSQL-IMPORT-001). Preview and execution
 * share the same parser; nothing is written during preview.
 */
@ConnectionInfoAspect
@RequestMapping("/api/rdb/import_preview")
@RestController
public class DbImportPreviewController {

    @Autowired
    private IDbImportPreviewService importPreviewService;

    @PostMapping("/preview")
    public DataResult<Map<String, Object>> preview(@Valid @RequestBody ImportPreviewRequest request) {
        return DataResult.of(importPreviewService.preview(request.getDataSourceId(), request.getDatabaseName(),
                request.getSchemaName(), request.getTableName(), request.getFilePath(),
                parseCsvOptions(request.getCsvOptions())));
    }

    @PostMapping("/execute")
    public DataResult<Map<String, Object>> execute(@Valid @RequestBody ImportExecuteRequest request) {
        return DataResult.of(importPreviewService.execute(
                request.getDataSourceId(), request.getDatabaseName(), request.getSchemaName(), request.getTableName(),
                request.getFilePath(), request.getCsvOptions() == null ? Map.of() : request.getCsvOptions(),
                request.getMappings(), request.getUnmappedTarget()));
    }

    private static Map<String, Object> parseCsvOptions(String csvOptions) {
        if (org.apache.commons.lang3.StringUtils.isBlank(csvOptions)) {
            return new java.util.LinkedHashMap<>();
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(csvOptions,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
        } catch (Exception e) {
            throw new BusinessException("import.preview.invalidCsvOptions", new Object[0], e);
        }
    }

    @Data
    public static class ImportPreviewRequest extends DataSourceBaseRequest {

        @NotBlank
        private String tableName;

        @NotBlank
        private String filePath;

        private String csvOptions;
    }

    @Data
    public static class ImportExecuteRequest extends DataSourceBaseRequest {

        @NotBlank
        private String tableName;

        @NotBlank
        private String filePath;

        private Map<String, Object> csvOptions;

        private List<Map<String, String>> mappings;

        private String unmappedTarget;
    }
}
