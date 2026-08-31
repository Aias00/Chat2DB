package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.model.request.db.DbTableQueryRequest;
import ai.chat2db.community.domain.api.service.db.IDbTableService;
import ai.chat2db.community.tools.wrapper.result.DataResult;
import ai.chat2db.community.web.api.converter.db.DbWebConverter;
import ai.chat2db.community.web.api.model.request.db.TableDetailQueryRequest;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DbTableControllerMaintenanceTest {

    @Test
    void maintenanceSqlReadsOperationTypeFromPostBodyUsedByFrontendService() throws Exception {
        AtomicReference<DbTableQueryRequest> capturedRequest = new AtomicReference<>();
        AtomicReference<String> capturedOperation = new AtomicReference<>();
        IDbTableService tableService = (IDbTableService) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {IDbTableService.class},
                (proxy, method, args) -> {
                    if ("maintenanceSql".equals(method.getName())) {
                        capturedRequest.set((DbTableQueryRequest) args[0]);
                        capturedOperation.set((String) args[1]);
                        return "REPAIR TABLE `shop`.`orders`";
                    }
                    throw new UnsupportedOperationException(method.getName());
                });

        DbTableController controller = new DbTableController(null);
        setField(controller, "tableService", tableService);
        setField(controller, "dbWebConverter", Mappers.getMapper(DbWebConverter.class));

        TableDetailQueryRequest request = new TableDetailQueryRequest();
        request.setDataSourceId(7L);
        request.setDatabaseName("shop");
        request.setTableName("orders");
        request.setOperationType("REPAIR");

        DataResult<String> result = controller.maintenanceSql(request);

        assertEquals("REPAIR TABLE `shop`.`orders`", result.getData());
        assertEquals("REPAIR", capturedOperation.get());
        assertEquals("orders", capturedRequest.get().getTableName());
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = DbTableController.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
