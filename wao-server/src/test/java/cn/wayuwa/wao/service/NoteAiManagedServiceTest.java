package cn.wayuwa.wao.service;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import cn.wayuwa.wao.common.*;
import cn.wayuwa.wao.dto.*;
import cn.wayuwa.wao.mapper.MemberPreferenceMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** 进程内 SQL 验证策略、幂等与来源；不冒充真实模型或 MySQL 验收。 */
class NoteAiManagedServiceTest {
 JdbcTemplate db; TransactionTemplate tx; NoteAiSettingsService settings; NoteAiManagedService managed;
 Map<String,String> values; KnowledgeService knowledge; TextPolishJobService text; ArticleImageService image;
 NoteTagService noteTags;
 @BeforeEach void setup() throws Exception {
  var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
  db=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
  db.execute("CREATE TABLE member(id VARCHAR(32) PRIMARY KEY)");db.update("INSERT INTO member VALUES('m')");
  db.execute("CREATE TABLE doc(id VARCHAR(32) PRIMARY KEY,author_id VARCHAR(32),kb_id VARCHAR(32),deleted_at TIMESTAMP,archived_at TIMESTAMP)");db.update("INSERT INTO doc VALUES('n','m','kb-notes',NULL,NULL)");
  db.execute("CREATE TABLE note_ai_workflow(doc_id VARCHAR(32) PRIMARY KEY,member_id VARCHAR(32),base_rev INT,original_title VARCHAR(256),source_markdown_hash CHAR(64),title_status VARCHAR(16),image_status VARCHAR(16),polish_status VARCHAR(16),last_error VARCHAR(500),updated_at TIMESTAMP)");
  try(var connection=ds.getConnection()){ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migration/V176__note_save_ai_settings.sql"));}
  values=new LinkedHashMap<>();var prefs=mock(MemberPreferenceMapper.class);
  when(prefs.find(anyString(),anyString())).thenAnswer(c->values.get(c.getArgument(1)));
  doAnswer(c->{values.put(c.getArgument(1),c.getArgument(2));return null;}).when(prefs).upsert(anyString(),anyString(),anyString());
  settings=new NoteAiSettingsService(db,prefs,new ObjectMapper());knowledge=mock(KnowledgeService.class);text=mock(TextPolishJobService.class);image=mock(ArticleImageService.class);
  ObjectProvider<KnowledgeService> provider=mock(ObjectProvider.class);when(provider.getObject()).thenReturn(knowledge);
  when(text.submit(any(),any(),any(),any(),any(),any(),any())).thenReturn(new TextPolishJobService.Result("job", "QUEUED", "等待", null, null, null));
  managed=new NoteAiManagedService(db,new ObjectMapper(),settings,provider,text,image,mock(NotePolishVersionService.class));
  noteTags=mock(NoteTagService.class);
  when(noteTags.list()).thenReturn(List.of());
  org.springframework.test.util.ReflectionTestUtils.setField(managed,"noteTags",noteTags);
 }
 DocDto doc(String body,String title,int rev){return new DocDto("n",title,"m",null,null,false,List.of(),List.of(),new RichDocContentDto(1,null,body),List.of(),"kb-notes",null,rev);}
 @Test void tagsContinueOnServerMergeManualChoicesAndRejectUnknownAndDeletedNames(){
  values.put(NoteAiSettingsService.DEFAULTS,"{\"title\":false,\"image\":false,\"polish\":false}");
  when(noteTags.list()).thenReturn(List.of("提示词","已删除"));
  when(noteTags.lockExisting()).thenReturn(List.of("提示词","手选"));
  var initial=doc("常用提示词整理与复用","首行",1);
  var current=new DocDto("n","新标题","m",null,null,false,List.of(),List.of(),initial.content(),List.of(),"kb-notes",null,2,List.of("手选"));
  when(knowledge.getDoc("n")).thenReturn(current);
  when(text.submit(eq("m"),any(),eq("NOTE_TAGS"),eq(initial.content().markdown()),any(),isNull(),isNull(),eq(List.of("提示词","已删除")))).thenReturn(new TextPolishJobService.Result("tag-job","QUEUED","等待",null,null,null));
  tx.executeWithoutResult(t->managed.saved(initial,"m","",null));
  assertThat(managed.status("n","m").tags()).isEqualTo("PENDING");
  tx.executeWithoutResult(t->managed.advance("n"));
  when(text.get("m","tag-job")).thenReturn(new TextPolishJobService.Result("tag-job","SUCCEEDED","完成","提示词\n新标签\n已删除\n提示词",null,null));
  when(knowledge.updateDerivedNote(eq("n"),any())).thenAnswer(c->{PatchDocRequest patch=c.getArgument(1);assertThat(patch.expectedRev()).isEqualTo(2);assertThat(patch.title()).isNull();assertThat(patch.content()).isNull();assertThat(patch.tags()).containsExactly("手选","提示词");return current;});
  tx.executeWithoutResult(t->managed.advance("n"));
  assertThat(managed.status("n","m").tags()).isEqualTo("DONE");
  verify(noteTags,never()).create(any());
 }
 @Test void noTagsOrNoMatchingTagSkipsWithoutCreatingOrChangingContent(){
  values.put(NoteAiSettingsService.DEFAULTS,"{\"title\":false,\"image\":false,\"polish\":false}");
  var initial=doc("常用提示词","标题",1);when(knowledge.getDoc("n")).thenReturn(initial);
  tx.executeWithoutResult(t->managed.saved(initial,"m","",null));
  assertThat(managed.status("n","m").tags()).isEqualTo("SKIPPED");
  when(noteTags.list()).thenReturn(List.of("工作"));
  when(noteTags.lockExisting()).thenReturn(List.of("工作"));
  when(text.submit(any(),any(),eq("NOTE_TAGS"),any(),any(),any(),any(),anyList())).thenReturn(new TextPolishJobService.Result("tag-job","QUEUED","等待",null,null,null));
  tx.executeWithoutResult(t->managed.manual("m","n","tags",1,"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
  tx.executeWithoutResult(t->managed.advance("n"));
  when(text.get("m","tag-job")).thenReturn(new TextPolishJobService.Result("tag-job","SUCCEEDED","完成","",null,null));
  tx.executeWithoutResult(t->managed.advance("n"));
  assertThat(managed.status("n","m").stages().get("tags").reasonCode()).isEqualTo("NO_MATCHING_TAGS");
  verify(knowledge,never()).updateDerivedNote(any(),any());
 }
 @Test void missingPreferenceDefaultsAndExplicitFalseStayDifferentFromCorruptPreference(){
  assertThat(settings.get("m").settings().title()).isTrue();assertThat(settings.get("m").configured()).isFalse();
  values.put(NoteAiSettingsService.DEFAULTS,"{\"title\":false,\"image\":false}");assertThat(settings.get("m").settings().title()).isFalse();assertThat(settings.get("m").settings().polish()).isTrue();
  values.put(NoteAiSettingsService.DEFAULTS,"bad-json");assertThatThrownBy(()->settings.get("m")).isInstanceOf(BadRequestException.class);
 }
 @Test void allEightCombinationsAreAppliedWithoutShortBodyGate(){
  for(int mask=0;mask<8;mask++){
   values.put(NoteAiSettingsService.DEFAULTS,"{\"title\":"+((mask&1)!=0)+",\"image\":"+((mask&2)!=0)+",\"polish\":"+((mask&4)!=0)+"}");
   var doc=doc("真实短内容"+mask,"首行",mask+1);tx.executeWithoutResult(s->managed.saved(doc,"m","",null));
   var status=managed.status("n","m");assertThat(status.title()).isEqualTo((mask&1)!=0?"PENDING":"SKIPPED");assertThat(status.polish()).isEqualTo((mask&4)!=0?"PENDING":"SKIPPED");assertThat(status.image()).isEqualTo("SKIPPED");
  }
 }
 @Test void duplicateSaveAndMetadataDoNotRestartButOldNoteBodyStarts(){
  var first=doc("日常记录","手动标题",1);tx.executeWithoutResult(s->managed.saved(first,"m","手动标题",null));
  String cycle=db.queryForObject("SELECT cycle_id FROM note_ai_save_signal",String.class);
  tx.executeWithoutResult(s->managed.saved(doc("日常记录","手动标题",2),"m","手动标题",null));
  assertThat(db.queryForObject("SELECT cycle_id FROM note_ai_save_signal",String.class)).isEqualTo(cycle);
  assertThat(managed.status("n","m").stages().get("title").reasonCode()).isEqualTo("MANUAL_TITLE");
  tx.executeWithoutResult(s->managed.saved(doc("新的日常记录","手动标题",3),"m","手动标题",null));
  assertThat(db.queryForObject("SELECT cycle_id FROM note_ai_save_signal",String.class)).isNotEqualTo(cycle);
 }
 @Test void preferenceFailureStillPersistsBodySignalAndNoDefaultTasks(){
  values.put(NoteAiSettingsService.DEFAULTS,"invalid");tx.executeWithoutResult(s->managed.saved(doc("内容已保存","标题",1),"m",null,null));
  assertThat(db.queryForObject("SELECT COUNT(*) FROM note_ai_save_signal",Integer.class)).isEqualTo(1);
  assertThat(db.queryForObject("SELECT COUNT(*) FROM note_ai_stage_attempt",Integer.class)).isZero();
  assertThat(managed.status("n","m").message()).contains("设置暂不可确认");
 }
 @Test void legacyProjectionCannotBeConsumedByAnOlderScheduler(){
  tx.executeWithoutResult(t->managed.saved(doc("真实记录".repeat(30),"首行",1),"m","",null));
  assertThat(managed.status("n","m").title()).isEqualTo("PENDING");
  assertThat(db.queryForObject("SELECT COUNT(*) FROM note_ai_workflow WHERE title_status IN ('PENDING','RUNNING') OR image_status IN ('PENDING','RUNNING') OR polish_status IN ('PENDING','RUNNING')",Integer.class)).isZero();
 }
 @Test void advancingOneNoteCannotExpireAnotherCurrentNoteForTheSameMember(){
  db.update("INSERT INTO doc VALUES('other','m','kb-notes',NULL,NULL)");
  var first=doc("第一篇记录","手动标题",1);
  var second=new DocDto("other","手动标题","m",null,null,false,List.of(),List.of(),new RichDocContentDto(1,null,"第二篇记录"),List.of(),"kb-notes",null,1);
  when(knowledge.getDoc("n")).thenReturn(first);when(knowledge.getDoc("other")).thenReturn(second);
  var selection=new CreateNoteRequest("手动标题",List.of(),first.content(),null,false,false,true);
  tx.executeWithoutResult(t->{managed.saved(first,"m","手动标题",selection);managed.saved(second,"m","手动标题",selection);managed.advance("other");});
  when(text.get(any(),any())).thenReturn(new TextPolishJobService.Result("job","SUCCEEDED","完成","第二篇润色",null,null));
  tx.executeWithoutResult(t->managed.advance("n"));
  assertThat(managed.status("other","m").polish()).isEqualTo("RUNNING");
 }
 @Test void closingAndReopeningIncrementsFenceAndCannotReviveOldAttempt(){
  tx.executeWithoutResult(s->managed.saved(doc("短内容","标题",1),"m","",null));
  tx.executeWithoutResult(s->settings.saveLegacy("m",NoteAiSettingsService.DEFAULTS,"{\"title\":false}"));
  assertThat(settings.get("m").settings().titleEpoch()).isEqualTo(1);
  tx.executeWithoutResult(s->settings.saveLegacy("m",NoteAiSettingsService.DEFAULTS,"{\"title\":true}"));
  when(knowledge.getDoc("n")).thenReturn(doc("短内容","标题",1));tx.executeWithoutResult(s->managed.advance("n"));
  assertThat(managed.status("n","m").title()).isEqualTo("SKIPPED");
  assertThat(managed.status("n","m").stages().get("title").reasonCode()).isEqualTo("DISABLED");
 }
 @Test void longBodyHasActionAndMachineCreationDoesNotStartAi(){
  tx.executeWithoutResult(s->managed.saved(doc("字".repeat(12001),"标题",1),"m","",null));
  assertThat(managed.status("n","m").stages().get("polish").action()).isEqualTo("SELECT_TEXT");
  tx.executeWithoutResult(s->NoteAiManagedService.withoutAuto("m",()->{managed.saved(doc("机器写入的新记录","标题",2),"m",null,null);return null;}));
  assertThat(managed.status("n","m").polish()).isEqualTo("SKIPPED");
 }

 @Test void stageCompletionOrdersPreserveOtherResultsAndManualTitleFence(){
  for(var order:List.of(List.of("title","image","polish"),List.of("title","polish","image"),List.of("image","title","polish"),List.of("image","polish","title"),List.of("polish","title","image"),List.of("polish","image","title"))){
   db.update("DELETE FROM note_ai_stage_attempt");db.update("DELETE FROM note_ai_save_signal");db.update("DELETE FROM note_ai_workflow");
   var current=new java.util.concurrent.atomic.AtomicReference<>(doc("记录".repeat(60),"首行",1));
   when(knowledge.getDoc("n")).thenAnswer(c->current.get());
   when(knowledge.updateDerivedNote(eq("n"),any())).thenAnswer(c->{PatchDocRequest r=c.getArgument(1);var old=current.get();assertThat(r.expectedRev()).isEqualTo(old.rev());var next=doc(r.content()==null?old.content().markdown():r.content().markdown(),r.title()==null?old.title():r.title(),old.rev()+1);current.set(next);return next;});
   when(text.submit(any(),any(),any(),any(),any(),any(),any())).thenAnswer(c->new TextPolishJobService.Result(c.getArgument(2),"QUEUED","等待",null,null,null));
   when(image.startNoteWorkflow(any(),any())).thenReturn(new ArticleImageJobDto("IMAGE","QUEUED","等待",null));
   Set<String> done=new HashSet<>();
   doAnswer(c->{String job=c.getArgument(1);return new TextPolishJobService.Result(job,done.contains(job)?"SUCCEEDED":"QUEUED","等待",job.equals("NOTE_TITLE")?"系统标题":"整理后的内容",null,null);}).when(text).get(any(),any());
   when(image.get(any(),eq("IMAGE"))).thenAnswer(c->new ArticleImageJobDto("IMAGE",done.contains("IMAGE")?"SUCCEEDED":"QUEUED","等待",done.contains("IMAGE")?"/api/images/trusted/raw":null));
   tx.executeWithoutResult(t->managed.saved(current.get(),"m","",null));tx.executeWithoutResult(t->managed.advance("n"));
   for(String stage:order){done.add(stage.equals("title")?"NOTE_TITLE":stage.equals("image")?"IMAGE":"NOTE_BODY");tx.executeWithoutResult(t->managed.advance("n"));}
   assertThat(current.get().title()).isEqualTo("系统标题");assertThat(current.get().content().markdown()).contains("/api/images/trusted/raw","整理后的内容");
   assertThat(managed.status("n","m").title()).isEqualTo("DONE");assertThat(managed.status("n","m").polish()).isEqualTo("DONE");
  }
 }
 @Test void stableManualRequestIsIdempotentAndRejectsDifferentOperation(){
  when(knowledge.getDoc("n")).thenReturn(doc("真实内容","手动标题",1));
  String request="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
  tx.executeWithoutResult(t->managed.manual("m","n","title",1,request));
  when(knowledge.getDoc("n")).thenReturn(doc("真实内容","新的标题",2));
  tx.executeWithoutResult(t->managed.manual("m","n","title",1,request));
  assertThat(db.queryForObject("SELECT COUNT(*) FROM note_ai_stage_attempt WHERE id=?",Integer.class,request)).isEqualTo(1);
  assertThatThrownBy(()->tx.executeWithoutResult(t->managed.manual("m","n","polish",2,request))).isInstanceOf(ConflictException.class);
 }
}
