package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.service.db.IDbImportPreviewService;
import ai.chat2db.community.domain.api.service.file.IImportFileRegistry;
import ai.chat2db.community.domain.api.service.file.IUploadFileService;
import ai.chat2db.community.domain.api.service.task.TaskService;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskTargetSnapshot;
import ai.chat2db.community.domain.api.model.task.TaskType;
import ai.chat2db.community.tools.wrapper.result.DataResult;
import ai.chat2db.community.web.api.aspect.connection.ConnectionInfoAspect;
import ai.chat2db.community.web.api.model.request.data.source.IDataSourceBaseRequestInfo;
import ai.chat2db.community.web.api.model.response.task.TaskSubmitResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
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

    @Autowired
    private TaskService taskService;

    @Autowired
    private IUploadFileService<MultipartFile> uploadFileService;

    @Autowired
    private IImportFileRegistry importFileRegistry;

    @PostMapping("/upload")
    public DataResult<String> upload(@RequestParam("file") MultipartFile file) {
        File temporary = null;
        try {
            temporary = uploadFileService.transferToTempFile(file);
            return DataResult.of(importFileRegistry.register(temporary, file.getOriginalFilename()));
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("Could not store import file", e);
        } finally {
            if (temporary != null && temporary.isFile()) {
                temporary.delete();
            }
        }
    }

    @PostMapping("/preview")
    public DataResult<Map<String, Object>> preview(@Valid @RequestBody ImportPreviewRequest request) {
        return DataResult.of(importPreviewService.preview(request.getDataSourceId(), request.getDatabaseName(),
                request.getTableName(), importFileRegistry.resolve(request.getFileId()),
                request.getImportOptions() == null ? Map.of() : request.getImportOptions()));
    }

    @PostMapping("/execute")
    public DataResult<TaskSubmitResponse> execute(@Valid @RequestBody ImportExecuteRequest request) {
        if (request.getMappings() == null || request.getMappings().isEmpty()) {
            throw new IllegalArgumentException("At least one source column must be mapped");
        }
        String strategy = request.getUnmappedTarget() == null ? "DEFAULT" : request.getUnmappedTarget().toUpperCase(java.util.Locale.ROOT);
        if (!"DEFAULT".equals(strategy) && !"NULL".equals(strategy)) {
            throw new IllegalArgumentException("Unsupported unmapped target strategy");
        }
        File file = importFileRegistry.resolve(request.getFileId());
        // Validate parser options, including visibility of the selected sheet, before queuing work.
        importPreviewService.preview(request.getDataSourceId(), request.getDatabaseName(), request.getTableName(),
                file, request.getImportOptions() == null ? Map.of() : request.getImportOptions());
        importFileRegistry.claim(request.getFileId());
        ImportTaskSpec spec = ImportTaskSpec.builder()
                .taskType(TaskType.DATA_FILE_IMPORT.name()).taskName("Import " + request.getTableName())
                .target(TaskTargetSnapshot.builder().dataSourceId(request.getDataSourceId())
                        .databaseName(request.getDatabaseName()).tableName(request.getTableName()).build())
                .sourceFile(file.getAbsolutePath()).importFileId(request.getFileId())
                .displayFileName(file.getName()).format(extension(file.getName()))
                .columnMappings(request.getMappings()).unmappedTarget(strategy)
                .importOptions(request.getImportOptions() == null ? Map.of() : request.getImportOptions()).build();
        try {
            return DataResult.of(new TaskSubmitResponse(taskService.submitImport(spec)));
        } catch (RuntimeException e) {
            importFileRegistry.release(request.getFileId());
            throw e;
        }
    }

    private static String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "CSV" : fileName.substring(dot + 1).toUpperCase(java.util.Locale.ROOT);
    }

    @Data
    public static class ImportPreviewRequest implements IDataSourceBaseRequestInfo {
        @NotNull
        private Long dataSourceId;

        @NotBlank
        private String databaseName;

        @NotBlank
        private String tableName;

        @NotBlank
        private String fileId;

        private Map<String, Object> importOptions;
    }

    @Data
    public static class ImportExecuteRequest implements IDataSourceBaseRequestInfo {
        @NotNull
        private Long dataSourceId;

        @NotBlank
        private String databaseName;

        @NotBlank
        private String tableName;

        @NotBlank
        private String fileId;

        private Map<String, Object> importOptions;

        private List<Map<String, String>> mappings;

        private String unmappedTarget;
    }
}
