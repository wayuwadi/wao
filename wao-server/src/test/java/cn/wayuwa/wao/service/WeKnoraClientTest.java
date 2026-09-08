package cn.wayuwa.wao.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class WeKnoraClientTest {
    private HttpServer server;
    private WeKnoraClient client;
    private final WeKnoraClient.Connection connection=new WeKnoraClient.Connection("test-kb","test-key");
    @BeforeEach void setUp()throws Exception{server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.start();client=new WeKnoraClient(new ObjectMapper(),"http://127.0.0.1:"+server.getAddress().getPort());}
    @AfterEach void close(){server.stop(0);}
    @Test void usesScopedKeyAndRealIndexingContract() {
        server.createContext("/knowledge-bases/test-kb",e->{assertEquals("test-key",e.getRequestHeaders().getFirst("X-API-Key"));assertNull(e.getRequestHeaders().getFirst("Authorization"));byte[] b="{\"success\":true,\"data\":{\"id\":\"test-kb\",\"name\":\"同步库\",\"indexing_strategy\":{\"wiki_enabled\":true,\"vector_enabled\":false}}}".getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(200,b.length);e.getResponseBody().write(b);e.close();});
        assertEquals("Wiki-only",client.check(connection).get("mode"));
    }
    @Test void ambiguousPostIsNotRetriedAndServerSecretsAreNotReturned() {
        AtomicInteger count=new AtomicInteger();
        server.createContext("/knowledge-bases/test-kb/knowledge/manual",e->{count.incrementAndGet();byte[] b="secret upstream response".getBytes();e.sendResponseHeaders(502,b.length);e.getResponseBody().write(b);e.close();});
        var source=WeKnoraSourceService.parts("docs","d1","标题","","正文").get(0);
        var failure=assertThrows(WeKnoraClient.ApiFailure.class,()->client.write(connection,null,source));
        assertTrue(failure.uncertain);assertFalse(failure.getMessage().contains("secret"));assertEquals(1,count.get());
    }
    @Test void incompleteRemoteListingFailsInsteadOfDeletingMissingRows() {
        server.createContext("/knowledge-bases/test-kb/knowledge",e->{byte[] b="{\"success\":true,\"data\":[],\"total\":100}".getBytes();e.sendResponseHeaders(200,b.length);e.getResponseBody().write(b);e.close();});
        assertThrows(WeKnoraClient.ApiFailure.class,()->client.list(connection));
    }
}
