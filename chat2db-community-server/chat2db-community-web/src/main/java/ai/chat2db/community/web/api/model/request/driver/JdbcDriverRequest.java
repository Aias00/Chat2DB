package ai.chat2db.community.web.api.model.request.driver;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class JdbcDriverRequest {
    @NotBlank
    @Pattern(regexp = "[\\p{L}_$][\\p{L}\\p{N}_$]*(?:\\.[\\p{L}_$][\\p{L}\\p{N}_$]*)+")
    String jdbcDriverClass;
    @NotBlank
    @Pattern(regexp = "[A-Za-z][A-Za-z0-9_-]*")
    String dbType;

    @NotEmpty
    List<@NotBlank String> jdbcDriver;
}
