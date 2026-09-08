package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.VaultCryptoService;
import com.fasterxml.jackson.databind.*;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class WeKnoraSyncServiceTest {
    final JdbcTemplate db=mock(JdbcTemplate.class);
    final WeKnoraSourceService source=mock(WeKnoraSourceService.class);
    final WeKnoraClient client=mock(WeKnoraClient.class);
    final VaultCryptoService crypto=mock(VaultCryptoService.class);
    final ObjectMapper json=new ObjectMapper();
    final WeKnoraSyncService service=new WeKnoraSyncService(db,mock(DataSource.class),mock(TransactionTemplate.class),json,crypto,source,client);
    final Map<String,Object> config=new HashMap<>(Map.of("kb_id","target","owner_member_id","owner","api_key_cipher",new byte[1],"api_key_nonce",new byte[1],"modules_json","[\"docs\"]","batch_limit",20,"sync_deletes",true));
    final WeKnoraSourceService.Source document=WeKnoraSourceService.parts("docs","d1","标题","","正文").get(0);
    Connection lock;
    @BeforeEach void setup()throws Exception {
        when(crypto.decrypt(any(),any())).thenReturn("private-key");
        when(source.snapshot("owner",List.of("docs"))).thenReturn(List.of(document));
        lock=mock(Connection.class);Statement statement=mock(Statement.class);ResultSet result=mock(ResultSet.class);
        when(lock.createStatement()).thenReturn(statement);when(statement.executeQuery(anyString())).thenReturn(result);when(result.next()).thenReturn(true);when(result.getInt(1)).thenReturn(1);
    }
    @AfterEach void close(){service.close();}
    void run(){ReflectionTestUtils.invokeMethod(service,"synchronize","run-1",config,lock);}
    void tracked(Map<String,Object> row){when(db.queryForList("SELECT source_key,remote_id,content_hash,status FROM weknora_sync_item")).thenReturn(List.of(row));}
    JsonNode remote() {return json.createObjectNode().put("id","remote-1").put("title",document.title());}
    @Test void unchangedContentDoesNotReprocessModel() {
        tracked(Map.of("source_key",document.key(),"remote_id","remote-1","content_hash",document.hash(),"status","synced"));
        when(client.list(any())).thenReturn(List.of(remote()));run();
        verify(client,never()).write(any(),any(),any());verify(client,never()).delete(any(),any());
    }
    @Test void lostCreateResponseAdoptsRemoteByStableMarkerInsteadOfCreatingAgain() {
        tracked(Map.of("source_key",document.key(),"status","creating"));when(client.list(any())).thenReturn(List.of(remote()));
        when(client.write(any(),eq("remote-1"),eq(document))).thenReturn(remote());run();
        verify(client).write(any(),eq("remote-1"),eq(document));
    }
    @Test void unresolvedAmbiguousCreateNeverBlindlyRetriesPost() {
        tracked(Map.of("source_key",document.key(),"status","uncertain"));when(client.list(any())).thenReturn(List.of());run();
        verify(client,never()).write(any(),any(),any());
    }
    @Test void failedSourceInventoryCannotDeleteAnyRemoteContent() {
        when(source.snapshot(anyString(),anyList())).thenThrow(new IllegalStateException("源查询失败"));
        assertThrows(IllegalStateException.class,this::run);verify(client,never()).delete(any(),any());verify(client,never()).list(any());
    }
    @Test void deletionIsLimitedToMappedCopiesAndCanBeDisabled() {
        when(source.snapshot(anyString(),anyList())).thenReturn(List.of());
        when(client.list(any())).thenReturn(List.of(remote(),json.createObjectNode().put("id","manual").put("title","手工文档")));
        tracked(Map.of("source_key",document.key(),"remote_id","remote-1","status","synced"));run();
        verify(client).delete(any(),eq("remote-1"));verify(client,never()).delete(any(),eq("manual"));
        clearInvocations(client);config.put("sync_deletes",false);run();verify(client,never()).delete(any(),any());
    }
    @Test void rejectsUnboundedBatchAndInvalidTime() {
        assertThrows(IllegalArgumentException.class,()->WeKnoraSyncService.validate(new WeKnoraSyncService.Settings("8f7e78c1-4933-4dec-bce3-f35dd8f4e698","",List.of("docs"),101,true,true,"02:00",0)));
        assertThrows(IllegalArgumentException.class,()->WeKnoraSyncService.validate(new WeKnoraSyncService.Settings("8f7e78c1-4933-4dec-bce3-f35dd8f4e698","",List.of("docs"),20,true,true,"25:00",0)));
    }
}
