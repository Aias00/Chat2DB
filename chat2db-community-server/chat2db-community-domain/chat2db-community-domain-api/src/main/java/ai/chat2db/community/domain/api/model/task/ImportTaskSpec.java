package ai.chat2db.community.domain.api.model.task;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImportTaskSpec implements TaskSpec {

    private String taskType;

    private String taskName;

    private TaskTargetSnapshot target;

    private String sourceFile;

    private String displayFileName;

    private String format;

    private String dataTimeFormat;

    private CsvOptions csvOptions;

    private java.util.List<java.util.Map<String, String>> mappings;

    private String unmappedTarget;
}
