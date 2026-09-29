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
    @Test void retiredSourcesAreLimitedToRemovedModules() {
        assertTrue(WeKnoraSyncService.retiredSource("memory:old:1"));
        assertTrue(WeKnoraSyncService.retiredSource("calendar:event:1"));
        assertFalse(WeKnoraSyncService.retiredSource("project-memory:active:1"));
        assertFalse(WeKnoraSyncService.retiredSource("docs:active:1"));
    }
    @Test void storedRetiredModulesDoNotBreakActiveSources() {
        config.put("modules_json","[\"docs\",\"memory\",\"workspaces\",\"assistant\"]");
        assertEquals(List.of("docs","assistant"),ReflectionTestUtils.invokeMethod(service,"modules",config));
    }
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
    @Test void cleanupBacklogCannotConsumeWritingBudget() {
        config.put("batch_limit",1);
        when(db.queryForList("SELECT source_key,remote_id,content_hash,status FROM weknora_sync_item")).thenReturn(List.of(
            Map.of("source_key","docs:old1:1","remote_id","old1","status","synced"),
            Map.of("source_key","docs:old2:1","remote_id","old2","status","synced")));
        when(client.list(any())).thenReturn(List.of());
        when(client.write(any(),isNull(),eq(document))).thenReturn(remote());run();
        verify(client,times(1)).delete(any(),any());
        verify(client).write(any(),isNull(),eq(document));
        var order=inOrder(client);order.verify(client).write(any(),isNull(),eq(document));order.verify(client).delete(any(),any());
        verify(db).update(contains("SET status=?,finished_at"),eq("backlog"),anyString(),eq("run-1"));
    }
    @Test void knowledgeRunCannotDeleteOtherSelectedOrExcludedModules() {
        config.put("modules_json","[\"docs\",\"chat\"]");
        tracked(Map.of("source_key","chat:day:1","remote_id","chat-1","status","synced"));
        when(client.list(any())).thenReturn(List.of());
        when(client.write(any(),isNull(),eq(document))).thenReturn(remote());
        ReflectionTestUtils.invokeMethod(service,"synchronize","run-1",config,lock,true);
        verify(source).snapshot("owner",List.of("docs"));
        verify(client,never()).delete(any(),any());
        verify(client).write(any(),isNull(),eq(document));
    }
    @Test void knowledgeRunAlsoRemovesRetiredSourcesWithoutDeletingChat() {
        config.put("modules_json","[\"docs\",\"memory\",\"chat\"]");
        when(db.queryForList("SELECT source_key,remote_id,content_hash,status FROM weknora_sync_item")).thenReturn(List.of(
            Map.of("source_key","workspaces:old:1","remote_id","old-remote","status","synced"),
            Map.of("source_key","chat:day:1","remote_id","chat-remote","status","synced")));
        when(client.list(any())).thenReturn(List.of());
        when(client.write(any(),isNull(),eq(document))).thenReturn(remote());
        ReflectionTestUtils.invokeMethod(service,"synchronize","run-1",config,lock,true);
        verify(client).delete(any(),eq("old-remote"));
        verify(client,never()).delete(any(),eq("chat-remote"));
    }
    @Test void obsoleteOnlyConfigurationCanRemoveItsTrackedCopies() {
        config.put("modules_json","[\"memory\",\"workspaces\"]");
        tracked(Map.of("source_key","workspaces:old:1","remote_id","old-remote","status","synced"));
        when(client.list(any())).thenReturn(List.of());
        run();
        verify(source,never()).snapshot(anyString(),anyList());
        verify(client).delete(any(),eq("old-remote"));
    }
    @Test void knowledgeChangeDetectionIgnoresOtherModulesAndUnchangedContent() {
        var record=new HashMap<String,Object>(Map.of("source_key",document.key(),"module_id","docs","content_hash",document.hash(),"status","synced","parse_status","completed","summary_status","completed"));
        var other=Map.<String,Object>of("source_key","chat:old:1","module_id","chat");
        assertFalse(WeKnoraSyncService.knowledgeChanged(List.of(document),List.of(record,other),List.of("docs"),true));
        record.put("content_hash","previous");
        assertTrue(WeKnoraSyncService.knowledgeChanged(List.of(document),List.of(record),List.of("docs"),true));
        assertTrue(WeKnoraSyncService.knowledgeChanged(List.of(),List.of(record),List.of("docs"),true));
        assertFalse(WeKnoraSyncService.knowledgeChanged(List.of(),List.of(record),List.of("docs"),false));
        assertTrue(WeKnoraSyncService.knowledgeChanged(List.of(document),List.of(record,
            Map.of("source_key","workspaces:old:1","module_id","workspaces")),List.of("docs"),false));
    }
    @Test void stateRequiresCurrentHashAndEveryLongDocumentPart() {
        var record=new HashMap<String,Object>(Map.of("content_hash",document.hash(),"desired_hash",document.hash(),"status","synced","parse_status","completed","summary_status","completed"));
        var tracked=new HashMap<String,Map<String,Object>>();tracked.put(document.key(),record);
        assertEquals("processed",WeKnoraSyncService.documentState(List.of(document),tracked));
        record.put("summary_status","processing");
        assertEquals("processing",WeKnoraSyncService.documentState(List.of(document),tracked));
        record.put("summary_status","failed");
        assertEquals("failed",WeKnoraSyncService.documentState(List.of(document),tracked));
        record.put("content_hash","old");record.put("desired_hash","old");
        assertEquals("pending",WeKnoraSyncService.documentState(List.of(document),tracked));
        var longParts=WeKnoraSourceService.parts("docs","long","长文","","正文".repeat(10000));
        var first=longParts.get(0);
        assertEquals("pending",WeKnoraSyncService.documentState(longParts,Map.of(first.key(),Map.of("content_hash",first.hash(),"status","synced","parse_status","completed","summary_status","completed"))));
    }
    @Test void disabledLiveSyncAndRecentCrossNodeCheckDoNotScanSources() {
        when(db.queryForList("SELECT cfg_value FROM system_setting WHERE cfg_key=?",String.class,"weknora-sync-live-enabled")).thenReturn(List.of("false"));
        assertNull(ReflectionTestUtils.invokeMethod(service,"queueKnowledgeRun",config));
        verifyNoInteractions(source);
        when(db.queryForList("SELECT cfg_value FROM system_setting WHERE cfg_key=?",String.class,"weknora-sync-live-enabled")).thenReturn(List.of("true"));
        when(db.queryForList("SELECT cfg_value FROM system_setting WHERE cfg_key=?",String.class,"weknora-sync-live-check")).thenReturn(List.of(Long.toString(System.currentTimeMillis()+240_000)));
        assertNull(ReflectionTestUtils.invokeMethod(service,"queueKnowledgeRun",config));
        verifyNoInteractions(source);
    }
    @Test void queryDoesNotExposeTargetWhenConfiguredOwnerCannotReadDocument() {
        config.put("owner_member_id","another-owner");
        when(source.documents("reader",List.of("d1"))).thenReturn(List.of(document));
        when(source.documents("another-owner",List.of("d1"))).thenReturn(List.of());
        when(db.queryForList(startsWith("SELECT id,owner_member_id,kb_id"))).thenReturn(List.of(config));
        var result=service.documentStates("reader",List.of("d1"));
        assertEquals("excluded",result.get(0).state());
        assertEquals("",result.get(0).knowledgeUrl());
        assertEquals("",result.get(0).sourceUrl());
        verifyNoInteractions(client,crypto);
    }
    @Test void sourceDiscoveryFailureIsRecordedWithoutRemoteWrites() {
        when(source.snapshot("owner",List.of("docs"))).thenThrow(new IllegalStateException("来源读取失败"));
        assertThrows(IllegalStateException.class,()->ReflectionTestUtils.invokeMethod(service,"queueKnowledgeRun",config));
        verify(db).update(startsWith("INSERT INTO weknora_sync_run"),anyString(),eq("来源读取失败"));
        verifyNoInteractions(client);
    }
}
