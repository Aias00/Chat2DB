package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.web.api.model.request.data.source.DataSourceBaseRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DbVariableControllerDatasourceContextTest {

    @Test
    void everyVariableRequestCarriesDatasourceContext() {
        assertTrue(DataSourceBaseRequest.class.isAssignableFrom(DbVariableController.VariableListRequest.class));
        assertTrue(DataSourceBaseRequest.class.isAssignableFrom(DbVariableController.VariableNameRequest.class));
        assertTrue(DataSourceBaseRequest.class.isAssignableFrom(DbVariableController.SetVariableRequest.class));
    }
}
