package cn.wayuwa.wao.service;
import cn.wayuwa.wao.common.VaultCryptoService;
import cn.wayuwa.wao.domain.*;
import cn.wayuwa.wao.mapper.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 明确合成的编排替身测试；不作为真实模型或 MySQL 持久化证据。 */
class NoteDiscoveryOrchestrationTest {
    NoteDiscoveryMapper db;LocalNoteAiService local;NoteDiscoveryService service;MockEnvironment env;
    AiAssistantRunEntity run;Map<String,Object> job, schedule, attempt;AtomicInteger count=new AtomicInteger(1);ObjectMapper json=new ObjectMapper();
    @BeforeEach void setup() throws Exception {
        db=mock(NoteDiscoveryMapper.class);local=mock(LocalNoteAiService.class);when(local.enabled()).thenReturn(true);when(local.ready()).thenReturn(true);when(local.capabilityId()).thenReturn("own-scope");
        var docs=mock(DocMapper.class);var runs=mock(AiAssistantRunMapper.class);var crypto=mock(VaultCryptoService.class);
        var doc=new DocEntity();doc.setId("synthetic-doc");doc.setAuthorId("m1");doc.setRev(1);doc.setContentMarkdown("合成事实");when(docs.selectById("synthetic-doc")).thenReturn(doc);when(db.eligible("m1","synthetic-doc")).thenReturn(true);
        var material=new NoteDiscoveryValidator.Material("synthetic-doc",1,"合成","合成事实",NoteDiscoveryIndexService.hash("合成事实"),"USER","2026-10-03");
        when(crypto.decrypt(any(byte[].class),any(byte[].class))).thenReturn(json.writeValueAsString(Map.of("materials",List.of(material),"existing",List.of())));
        schedule=new HashMap<>(Map.of("member_id","m1","enabled",true,"enable_generation",1L,"active_job_id","j","lease_epoch",1L));
        job=new HashMap<>(Map.of("id","j","member_id","m1","enable_generation",1L,"status","GENERATING","phase","GENERATING","ai_run_id","r","execution_scope","own-scope","pipeline_version",NoteDiscoveryService.PIPELINE,"prompt_version",NoteDiscoveryValidator.PROMPT_VERSION));
        job.put("input_cipher",new byte[]{1});job.put("input_nonce",new byte[]{2});job.put("input_seq",1L);job.put("attempt_count",1L);
        attempt=new HashMap<>(Map.of("id","a","job_id","j","status","QUEUED","execution_scope","own-scope","budget_day","2026-10-03","attempt_no",1L));
        when(db.lock("m1")).thenAnswer(call->schedule);when(db.claim(eq("m1"),anyString(),eq(120))).thenAnswer(call->{schedule.put("lease_token",call.getArgument(1));return 1;});
        when(db.one(anyString(),any(Object[].class))).thenAnswer(call->{String sql=call.getArgument(0);if(sql.contains("COUNT(*)"))return Map.of("n",count.get());if(sql.contains("AS day"))return Map.of("day","2026-10-03");if(sql.contains("note_discovery_ai_attempt"))return attempt;if(sql.contains("note_mining_job"))return job;if(sql.contains("note_discovery_schedule"))return schedule;if(sql.contains("scope='MEMBER'"))return Map.of("started_calls",1);if(sql.contains("scope='GLOBAL'"))return Map.of("started_calls",0);return null;});
        when(db.update(anyString(),any(Object[].class))).thenReturn(1);
        run=new AiAssistantRunEntity();run.setId("r");run.setMemberId("m1");run.setAiCapabilityId("own-scope");run.setUsagePurpose(NoteDiscoveryService.GENERATE);run.setStatus("succeeded");run.setResponseText("invalid JSON");when(runs.selectById("r")).thenReturn(run);
        env=new MockEnvironment().withProperty("wao.ai.discoveries.enabled","true");var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID(),"sa","");
        service=new NoteDiscoveryService(db,docs,runs,local,mock(LocalCodexModelService.class),mock(MemberPreferenceMapper.class),new NoteDiscoveryIndexService(json),new NoteDiscoveryValidator(json),json,crypto,env,new DataSourceTransactionManager(ds));
    }
    boolean updateContains(String fragment) {return mockingDetails(db).getInvocations().stream().anyMatch(i->i.getMethod().getName().equals("update")&&i.getArgument(0,String.class).contains(fragment));}
    boolean writtenValue(String value) {return mockingDetails(db).getInvocations().stream().filter(i->i.getMethod().getName().equals("update")).flatMap(i->Arrays.stream(i.getArguments())).anyMatch(value::equals);}
    @Test void pausedQueuedRunRefundsWithoutStartingModel() {schedule.put("enabled",false);assertThat(service.beforeExecution(run)).isFalse();assertThat(updateContains("reserved_calls=GREATEST")).isTrue();assertThat(updateContains("started_calls=started_calls+1")).isFalse();}
    @Test void changedAcceptanceMemberOrDocumentScopeStopsQueuedCall() {env.withProperty("wao.ai.discoveries.member-ids","another-member");assertThat(service.beforeExecution(run)).isFalse();env.withProperty("wao.ai.discoveries.member-ids","m1").withProperty("wao.ai.discoveries.document-ids","another-document");assertThat(service.beforeExecution(run)).isFalse();}
    @Test void generationAndOwnerAreCheckedBeforeActualExecution() {schedule.put("enable_generation",2L);assertThat(service.beforeExecution(run)).isFalse();schedule.put("enable_generation",1L);run.setMemberId("other-member");assertThat(service.beforeExecution(run)).isFalse();}
    @Test void actualStartConsumesReservedCallOnce() {assertThat(service.beforeExecution(run)).isTrue();assertThat(updateContains("started_calls=started_calls+1")).isTrue();assertThat(updateContains("status='RUNNING'")).isTrue();}
    @Test void crossDayBudgetUsesConfiguredLimitAndDefersInsteadOfCalling() {attempt.put("budget_day","2026-10-02");env.withProperty("wao.ai.discoveries.member-daily-calls","1");assertThat(service.beforeExecution(run)).isFalse();assertThat(updateContains("BUDGET_DEFERRED")).isTrue();assertThat(updateContains("started_calls=started_calls+1")).isFalse();}
    @Test void malformedOutputIsRetriedOnceAndThenRealFailure() {service.advance("m1");assertThat(updateContains("result_type='PROTOCOL_ERROR'")).isTrue();assertThat(updateContains("retry_at=TIMESTAMPADD(MINUTE,5")).isTrue();clearInvocations(db);count.set(2);job.put("attempt_count",2L);service.advance("m1");assertThat(writtenValue("FAILED")).isTrue();assertThat(writtenValue("PROTOCOL_ERROR")).isTrue();verify(local,never()).enqueue(anyString(),anyString(),anyString(),anyString());}
    @Test void callFailureHasFiniteRetryAndRemainsDifferentFromEmptySuccess() {run.setStatus("failed");service.advance("m1");assertThat(updateContains("result_type='CALL_FAILED'")).isTrue();clearInvocations(db);attempt.put("attempt_no",2L);job.put("attempt_count",2L);service.advance("m1");assertThat(writtenValue("FAILED")).isTrue();assertThat(writtenValue("CALL_FAILED")).isTrue();assertThat(writtenValue("NO_FINDING")).isFalse();}
    @Test void missingUsageIsUnknownAndDoesNotInventZeroTokens() {service.executionFinished(run,new LocalNoteCodexProcess.Result("output",null),12L,null);assertThat(updateContains("usage_known_calls=usage_known_calls+1")).isFalse();}
}
