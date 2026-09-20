package cn.wayuwa.wao.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.wayuwa.wao.auth.NodeSessionBridge;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;

class NodeSessionReadinessTest {
    private static final String SQL = "SELECT IF(@@global.read_only=0 AND @@global.super_read_only=0,1,0)";
    @Test void writableAuthorityIsReadyButReadOnlyAndUnavailableAuthorityAreNot() {
        var jdbc = mock(JdbcTemplate.class);
        var controller = new NodeSessionController(mock(NodeSessionBridge.class), jdbc);
        when(jdbc.queryForObject(SQL, Integer.class)).thenReturn(1, 0, null)
                .thenThrow(new DataAccessResourceFailureException("private connection detail"));
        var ready = new MockHttpServletResponse();
        assertEquals(1, controller.ready(ready).data().get("protocol"));
        assertEquals(200, ready.getStatus());
        for (int i = 0; i < 3; i++) {
            var unavailable = new MockHttpServletResponse();
            var body = controller.ready(unavailable);
            assertEquals(503, unavailable.getStatus());
            assertNull(body.data());
            assertFalse(body.message().contains("private connection detail"));
            assertEquals("no-store", unavailable.getHeader("Cache-Control"));
        }
    }
}
