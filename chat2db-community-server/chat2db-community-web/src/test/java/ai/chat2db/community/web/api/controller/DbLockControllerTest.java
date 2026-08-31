package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.web.api.model.request.data.source.DataSourceBaseRequest;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.GetMapping;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class DbLockControllerTest {

    @Test
    void viewRequiresOneDatasourceRequestObject() throws Exception {
        Method method = DbLockController.class.getDeclaredMethod("view", DataSourceBaseRequest.class);

        GetMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, GetMapping.class);

        assertNotNull(mapping);
        assertArrayEquals(new String[] {"/view"}, mapping.value());
        assertEquals(DataSourceBaseRequest.class, method.getParameterTypes()[0]);
    }
}
