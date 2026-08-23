package ai.chat2db.community.web.api.model.response.db;

import lombok.Data;

import java.util.List;

@Data
public class AccountResponse {
    private String user;
    private String host;
    private String displayName;
    private String authenticationPlugin;
    private Boolean locked;
    private Boolean role;
    private List<String> defaultRoles;
}
