package cn.wayuwa.wao.acceptance;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class LocalWorkspaceCliMysqlAcceptanceGuardTest {
    @Test void neverAcceptsRemoteOrMalformedDatabaseTargets() {
        assertThrows(IllegalArgumentException.class, () -> LocalWorkspaceCliMysqlAcceptance.validateLocalHost("mysql.example", "3306"));
        assertThrows(IllegalArgumentException.class, () -> LocalWorkspaceCliMysqlAcceptance.validateLocalHost("127.0.0.1", "0"));
        assertThrows(IllegalArgumentException.class, () -> LocalWorkspaceCliMysqlAcceptance.validateLocalHost("localhost", "65536"));
        assertDoesNotThrow(() -> LocalWorkspaceCliMysqlAcceptance.validateLocalHost("127.0.0.1", "3306"));
    }
}
