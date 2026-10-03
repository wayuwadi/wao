package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.*;
import cn.wayuwa.wao.domain.*;
import cn.wayuwa.wao.mapper.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 进程内事务/状态证据；MySQL 方言领取另在登记验收记录上验证。 */
class NoteDiscoveryStateTest {
    JdbcTemplate jdbc; NoteDiscoveryMapper db; TransactionTemplate tx; NoteDiscoveryChangeService changes;
    NoteDiscoveryService service; DocMapper docs; MockEnvironment env;
    @BeforeEach void setup() {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE","sa","");
        jdbc=new JdbcTemplate(ds);var manager=new DataSourceTransactionManager(ds);tx=new TransactionTemplate(manager);
        jdbc.execute("CREATE TABLE kb(id VARCHAR(32) PRIMARY KEY,notes BOOLEAN,deleted_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE doc(id VARCHAR(32) PRIMARY KEY,kb_id VARCHAR(32),author_id VARCHAR(32),rev INT,content_markdown CLOB,deleted_at TIMESTAMP,archived_at TIMESTAMP,auto BOOLEAN DEFAULT FALSE)");
        jdbc.execute("CREATE TABLE note_discovery_schedule(member_id VARCHAR(32) PRIMARY KEY,enabled BOOLEAN DEFAULT FALSE,enable_generation BIGINT DEFAULT 0,change_seq BIGINT DEFAULT 0,processed_seq BIGINT DEFAULT 0,index_seq BIGINT DEFAULT 0,processed_index_seq BIGINT DEFAULT 0,next_run_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,active_job_id VARCHAR(36) UNIQUE,current_artifact_id VARCHAR(36),last_published_at TIMESTAMP,lease_token VARCHAR(32),lease_epoch BIGINT DEFAULT 0,lease_until TIMESTAMP,version BIGINT DEFAULT 0)");
        jdbc.execute("CREATE TABLE note_discovery_note_state(doc_id VARCHAR(32) PRIMARY KEY,member_id VARCHAR(32),doc_rev INT,body_fingerprint VARCHAR(64),user_body_fingerprint VARCHAR(64),user_basis_rev INT,change_origin VARCHAR(24),provenance VARCHAR(24),change_seq BIGINT DEFAULT 0,processed_seq BIGINT DEFAULT 0,stable_after TIMESTAMP,eligible BOOLEAN,indexed_rev INT,execution_scope VARCHAR(64))");
        jdbc.execute("CREATE TABLE note_discovery_index_term(doc_id VARCHAR(32),term_text VARCHAR(128))");
        jdbc.execute("CREATE TABLE note_polish_version(doc_id VARCHAR(32),member_id VARCHAR(32),source_markdown CLOB,polished_markdown CLOB,verified_ai_job_id VARCHAR(36),verified_output_fingerprint VARCHAR(64))");
        jdbc.execute("CREATE TABLE note_mining_job(id VARCHAR(36) PRIMARY KEY,member_id VARCHAR(32),origin VARCHAR(16),status VARCHAR(24),result_type VARCHAR(32),finished_at TIMESTAMP,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE note_mining_artifact(id VARCHAR(36) PRIMARY KEY,member_id VARCHAR(32),origin VARCHAR(16),validity VARCHAR(24),invalid_reason VARCHAR(32),content_text CLOB,rationale_text CLOB,headline VARCHAR(100),expires_at TIMESTAMP,topic_id VARCHAR(64),conclusion_hash VARCHAR(64),evidence_hash VARCHAR(64))");
        jdbc.execute("CREATE TABLE note_mining_artifact_source(artifact_id VARCHAR(36),doc_id VARCHAR(32),doc_rev INT,doc_title VARCHAR(255),excerpt VARCHAR(500),source_ord INT,body_fingerprint VARCHAR(64),quotes_json CLOB)");
        jdbc.execute("CREATE TABLE note_discovery_feedback(member_id VARCHAR(32),item_id VARCHAR(96),item_kind VARCHAR(24),saved_at TIMESTAMP,dismissed_at TIMESTAMP,correction_text VARCHAR(500),corrected_at TIMESTAMP,topic_id VARCHAR(64),conclusion_hash VARCHAR(64),evidence_hash VARCHAR(64),version BIGINT DEFAULT 0,PRIMARY KEY(member_id,item_id))");
        jdbc.execute("CREATE TABLE note_discovery_topic_state(member_id VARCHAR(32),topic_id VARCHAR(64),object_key VARCHAR(200),state VARCHAR(16),PRIMARY KEY(member_id,topic_id))");
        db=new NoteDiscoveryMapper(jdbc) {
            @Override public int update(String sql,Object... args) {
                if(sql.startsWith("UPDATE note_mining_artifact a JOIN note_mining_artifact_source"))
                    sql="UPDATE note_mining_artifact SET validity='INVALID',invalid_reason=? WHERE id IN (SELECT artifact_id FROM note_mining_artifact_source WHERE doc_id=?) AND origin='AUTO' AND validity='VALID'";
                if(sql.startsWith("UPDATE note_discovery_topic_state t JOIN"))return 0; // 这里只测反馈独立字段；主题 SQL 在 MySQL 验证。
                return super.update(sql,args);
            }
        };
        env=new MockEnvironment().withProperty("wao.ai.discoveries.enabled","true");var index=new NoteDiscoveryIndexService(new ObjectMapper());
        changes=new NoteDiscoveryChangeService(db,index,env);docs=mock(DocMapper.class);
        var local=mock(LocalNoteAiService.class);when(local.enabled()).thenReturn(true);when(local.ready()).thenReturn(true);
        service=new NoteDiscoveryService(db,docs,mock(AiAssistantRunMapper.class),local,mock(LocalCodexModelService.class),mock(MemberPreferenceMapper.class),index,new NoteDiscoveryValidator(new ObjectMapper()),new ObjectMapper(),mock(VaultCryptoService.class),env,manager);
        jdbc.update("INSERT INTO kb VALUES('kb-notes',TRUE,NULL)");
        jdbc.update("INSERT INTO doc(id,kb_id,author_id,rev,content_markdown) VALUES('d1','kb-notes','m1',1,'同一批名称又核对一次'),('d2','kb-notes','m1',1,'上周也核对这批名称')");
    }
    @AfterEach void close() { jdbc.execute("SHUTDOWN"); }
    DocEntity doc(String id,String text,int rev) { var d=new DocEntity();d.setId(id);d.setKbId("kb-notes");d.setAuthorId("m1");d.setContentMarkdown(text);d.setRev(rev);return d; }
    void save(DocEntity d,String origin) { tx.executeWithoutResult(status->{jdbc.update("UPDATE doc SET content_markdown=?,rev=? WHERE id=?",d.getContentMarkdown(),d.getRev(),d.getId());changes.saved(d,origin);}); }
    long sequence() { return NoteDiscoveryMapper.number(db.one("SELECT * FROM note_discovery_schedule WHERE member_id='m1'"),"change_seq"); }
    void artifact() {
        when(docs.selectById("d1")).thenReturn(doc("d1","同一批名称又核对一次",1));when(docs.selectById("d2")).thenReturn(doc("d2","上周也核对这批名称",1));
        jdbc.update("INSERT INTO note_mining_artifact(id,member_id,origin,validity,content_text,rationale_text,headline,expires_at) VALUES('a','m1','AUTO','VALID','私有结论','私有解释','具体联系',TIMESTAMPADD(DAY,7,CURRENT_TIMESTAMP))");
        for(String id:List.of("d1","d2")){var d=docs.selectById(id);jdbc.update("INSERT INTO note_mining_artifact_source(artifact_id,doc_id,doc_rev,doc_title,excerpt,source_ord,body_fingerprint) VALUES('a',?,1,'原文',?,0,?)",id,d.getContentMarkdown(),NoteDiscoveryIndexService.hash(d.getContentMarkdown()));}
    }
    @Test void repeatedSavesMergeAndUnchangedBodyDoesNotAdvanceFacts() {
        save(doc("d1","同一批名称又核对一次",1),"USER");save(doc("d1","同一批名称又核对一次  \r\n",2),"USER");assertThat(sequence()).isEqualTo(1);
        save(doc("d1","同一批名称已经对齐",3),"USER");assertThat(sequence()).isEqualTo(2);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM note_discovery_note_state",Integer.class)).isEqualTo(1);
    }
    @Test void signalsCannotBeWrittenOutsideBodyTransaction() { assertThatThrownBy(()->changes.saved(doc("d1","正文",2),"USER")).hasMessageContaining("同事务"); }
    @Test void laterSignalFailureRollsBackAlreadyWrittenBody() {
        assertThatThrownBy(()->tx.executeWithoutResult(status->{jdbc.update("UPDATE doc SET content_markdown='未提交新文' WHERE id='d1'");changes.saved(doc("d1","未提交新文",2),"USER");throw new IllegalStateException("注入事务失败");})).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT content_markdown FROM doc WHERE id='d1'",String.class)).isEqualTo("同一批名称又核对一次");assertThat(sequence()).isZero();
    }
    @Test void aiDerivedAndMetadataDoNotCreateIndependentFacts() {
        save(doc("d1","用户事实",1),"USER");save(doc("d1","整理后的用户事实",2),"AI_DERIVED");save(doc("d1","整理后的用户事实",3),"USER");
        assertThat(sequence()).isEqualTo(1);var state=db.one("SELECT * FROM note_discovery_note_state WHERE doc_id='d1'");assertThat(state.get("provenance")).isEqualTo("AI_DERIVED");assertThat(state.get("user_basis_rev")).isEqualTo(1);
    }
    @Test void verifiedPolishWrittenByOldBrowserIsDerived() {
        save(doc("d1","人工原文",1),"USER");jdbc.update("INSERT INTO note_polish_version VALUES('d1','m1','人工原文','已验证整理','pj-verified',?)",NoteDiscoveryIndexService.hash("已验证整理"));save(doc("d1","已验证整理",2),"USER");assertThat(sequence()).isEqualTo(1);
    }
    @Test void editDuringBatchLeavesNewerPendingSequence() {
        save(doc("d1","第一份事实",1),"USER");long frozen=sequence();save(doc("d1","第二份新增事实",2),"USER");
        jdbc.update("UPDATE note_discovery_note_state SET processed_seq=LEAST(change_seq,?) WHERE doc_id='d1'",frozen);
        var state=db.one("SELECT * FROM note_discovery_note_state WHERE doc_id='d1'");assertThat(NoteDiscoveryMapper.number(state,"change_seq")).isGreaterThan(NoteDiscoveryMapper.number(state,"processed_seq"));
    }
    @Test void onlyOneOfConcurrentInstancesCanClaim() throws Exception {
        db.ensure("m1");jdbc.update("UPDATE note_discovery_schedule SET enabled=TRUE WHERE member_id='m1'");var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try { var one=pool.submit(()->{start.await();return db.claim("m1","one",120);});var two=pool.submit(()->{start.await();return db.claim("m1","two",120);});start.countDown();assertThat(one.get()+two.get()).isEqualTo(1); }finally{pool.shutdownNow();}
    }
    @Test void expiredLeaseIsRecoveredAndOldOwnerCannotRenew() {
        db.ensure("m1");jdbc.update("UPDATE note_discovery_schedule SET enabled=TRUE WHERE member_id='m1'");assertThat(db.claim("m1","old",120)).isEqualTo(1);
        jdbc.update("UPDATE note_discovery_schedule SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP) WHERE member_id='m1'");assertThat(db.claim("m1","new",120)).isEqualTo(1);assertThat(db.renew("m1","old",1,120)).isZero();assertThat(db.renew("m1","new",2,120)).isEqualTo(1);
    }
    @Test void recreatedMapperSeesPersistentBatchAndLease() {
        db.ensure("m1");jdbc.update("UPDATE note_discovery_schedule SET active_job_id='frozen-batch' WHERE member_id='m1'");var recovered=new NoteDiscoveryMapper(jdbc);
        assertThat(recovered.lock("m1").get("active_job_id")).isEqualTo("frozen-batch");
    }
    @Test void noPreferenceMeansOffAndOldManualHistoryDoesNotEnable() {
        jdbc.update("INSERT INTO note_mining_job(id,member_id,origin,status) VALUES('old','m1','LEGACY','SUCCEEDED')");assertThat(service.preference("m1").get("enabled")).isEqualTo(false);assertThat(service.current("m1").get("automationState")).isEqualTo("OFF");
    }
    @Test void preferenceUsesCasAndPauseChangesGeneration() {
        var first=service.savePreference("m1",true,0);assertThat(first.get("version")).isEqualTo(1L);
        assertThatThrownBy(()->service.savePreference("m1",false,0)).isInstanceOf(ConflictException.class);service.savePreference("m1",false,1);
        assertThat(db.lock("m1").get("enable_generation")).isEqualTo(2L);assertThat(service.current("m1").get("automationState")).isEqualTo("PAUSED");
    }
    @Test void pauseCancelsLateBatchButRetainsSavedContent() {
        service.savePreference("m1",true,0);artifact();service.feedback("m1","a",0,Map.of("saved",true));
        jdbc.update("INSERT INTO note_mining_job(id,member_id,origin,status) VALUES('late','m1','AUTO','GENERATING')");jdbc.update("UPDATE note_discovery_schedule SET active_job_id='late' WHERE member_id='m1'");service.savePreference("m1",false,1);
        assertThat(jdbc.queryForObject("SELECT status FROM note_mining_job WHERE id='late'",String.class)).isEqualTo("CANCELLED");assertThat(((Map<?,?>)service.item("m1","a").get("feedback")).get("saved")).isEqualTo(true);
    }
    @Test void saveDismissAndCorrectionAreIndependentAndVersioned() {
        artifact();service.feedback("m1","a",0,Map.of("saved",true));service.feedback("m1","a",1,Map.of("dismissed",true,"correction","对象需要补充"));
        var f=(Map<?,?>)service.item("m1","a").get("feedback");assertThat(f.get("saved")).isEqualTo(true);assertThat(f.get("dismissed")).isEqualTo(true);assertThat(f.get("correction")).isEqualTo("对象需要补充");
        service.feedback("m1","a",2,Map.of("dismissed",false));assertThat(((Map<?,?>)service.item("m1","a").get("feedback")).get("saved")).isEqualTo(true);
        assertThatThrownBy(()->service.feedback("m1","a",2,Map.of("saved",false))).isInstanceOf(ConflictException.class);
    }
    @Test void collectionDoesNotBypassDeletedOrArchivedSource() {
        artifact();service.feedback("m1","a",0,Map.of("saved",true));jdbc.update("UPDATE doc SET archived_at=CURRENT_TIMESTAMP WHERE id='d2'");
        var restricted=service.item("m1","a");assertThat(restricted.get("restricted")).isEqualTo(true);assertThat(restricted.get("evidence")).isEqualTo(List.of());assertThat(restricted.get("observation")).isEqualTo("");assertThatThrownBy(()->service.requireSource("m1","a","d1")).isInstanceOf(NotFoundException.class);
    }
    @Test void otherMemberCannotReadItemOrUseSourceId() { artifact();assertThatThrownBy(()->service.item("m2","a")).isInstanceOf(NotFoundException.class);assertThatThrownBy(()->service.requireSource("m1","a","not-allowed")).isInstanceOf(NotFoundException.class);assertThat(db.eligible("m2","d1")).isFalse(); }
    @Test void bodyChangesInvalidateOldRecommendationAndRemoveTerms() {
        artifact();save(doc("d1","同一批名称又核对一次",1),"USER");jdbc.update("INSERT INTO note_discovery_index_term VALUES('d1','名称')");save(doc("d1","已经对齐，不用核对",2),"USER");
        assertThat(jdbc.queryForObject("SELECT validity FROM note_mining_artifact WHERE id='a'",String.class)).isEqualTo("INVALID");assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM note_discovery_index_term",Integer.class)).isZero();
    }
    @Test void restrictedLocalAcceptanceDoesNotCollectOtherDocuments() {
        env.withProperty("wao.ai.discoveries.document-ids","d2");save(doc("d1","其他小记",2),"USER");assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM note_discovery_note_state",Integer.class)).isZero();
    }
    @Test void acknowledgingFrozenMaterialsDoesNotClearUnselectedOrNewerSignal() {
        save(doc("d1","第一份事实",1),"USER");save(doc("d2","未被选入的事实",1),"USER");long frozen=sequence();save(doc("d1","运行中新增事实",2),"USER");
        var frozenMaterial=new NoteDiscoveryValidator.Material("d1",1,"小记","第一份事实","hash","USER","2026-10-03");
        Long acknowledged=org.springframework.test.util.ReflectionTestUtils.invokeMethod(service,"acknowledge","m1",frozen,List.of(frozenMaterial));
        assertThat(acknowledged).isEqualTo(1L);
        assertThat(NoteDiscoveryMapper.number(db.one("SELECT * FROM note_discovery_note_state WHERE doc_id='d1'"),"processed_seq")).isEqualTo(frozen);
        assertThat(NoteDiscoveryMapper.number(db.one("SELECT * FROM note_discovery_note_state WHERE doc_id='d2'"),"processed_seq")).isZero();
    }
    @Test void expiredUnsavedArtifactKeepsSuppressionAndAllowsOwnedUndo() {
        artifact();jdbc.update("UPDATE note_mining_artifact SET topic_id='topic',conclusion_hash='conclusion',evidence_hash='evidence' WHERE id='a'");
        service.feedback("m1","a",0,Map.of("dismissed",true));jdbc.update("DELETE FROM note_mining_artifact WHERE id='a'");
        assertThat(service.item("m1","a")).containsEntry("restricted",true).containsEntry("headline","这条发现已过期");
        assertThat(db.one("SELECT * FROM note_discovery_feedback WHERE item_id='a'")).containsEntry("conclusion_hash","conclusion");
        service.feedback("m1","a",1,Map.of("dismissed",false));assertThat(db.one("SELECT * FROM note_discovery_feedback WHERE item_id='a'")).containsEntry("conclusion_hash","conclusion").containsEntry("dismissed_at",null);
        assertThatThrownBy(()->service.item("another-member","a")).isInstanceOf(NotFoundException.class);
    }
    @Test void topicIdentityUsesOwnedEvidenceAndObjectTermsInsteadOfOnlyModelTitle() throws Exception {
        artifact();jdbc.update("UPDATE note_mining_artifact SET topic_id='stable-topic' WHERE id='a'");jdbc.update("INSERT INTO note_discovery_topic_state VALUES('m1','stable-topic','同一批客户跨系统名称不一致导致重复核对','OPEN')");
        var evidence=new ObjectMapper().readTree("[{\"docId\":\"d1\"},{\"docId\":\"d2\"}]");
        String matching=org.springframework.test.util.ReflectionTestUtils.invokeMethod(service,"stableTopic","m1","客户名称反复核对",evidence);assertThat(matching).isEqualTo("stable-topic");
        String unrelated=org.springframework.test.util.ReflectionTestUtils.invokeMethod(service,"stableTopic","m1","客户会议颜色",evidence);assertThat(unrelated).isNotEqualTo("stable-topic");
        String foreign=org.springframework.test.util.ReflectionTestUtils.invokeMethod(service,"stableTopic","other-member","客户名称反复核对",evidence);assertThat(foreign).isNotEqualTo("stable-topic");
    }
}
