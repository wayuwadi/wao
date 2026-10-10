package cn.wayuwa.wao.service;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import cn.wayuwa.wao.common.*;
import cn.wayuwa.wao.dto.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;

/** SQL、事务和来源准确性测试；模型、身份为明确的测试替身。 */
class NoteOriginalServiceTest extends NoteAiManagedServiceTest {
 NoteOriginalService originals; ObjectMapper json=new ObjectMapper();
 @BeforeEach void originalsSetup() throws Exception {
  try(var c=db.getDataSource().getConnection()){ScriptUtils.executeSqlScript(c,new ClassPathResource("db/migration/V177__note_original_snapshots.sql"));}
  db.execute("CREATE TABLE note_polish_version(doc_id VARCHAR(32),member_id VARCHAR(32),source_markdown CLOB,polished_markdown CLOB,updated_at TIMESTAMP)");
  db.execute("CREATE TABLE image_blob(id VARCHAR(32) PRIMARY KEY)");db.execute("CREATE TABLE file_blob(id VARCHAR(32) PRIMARY KEY)");
  db.execute("CREATE TABLE text_polish_job(id VARCHAR(34) PRIMARY KEY,member_id VARCHAR(32),original_text CLOB)");
  ObjectProvider<KnowledgeService> provider=mock(ObjectProvider.class);when(provider.getObject()).thenReturn(knowledge);
  originals=new NoteOriginalService(db,json,settings,provider,text,image);ReflectionTestUtils.setField(managed,"originals",originals);
 }
 @Test void draftPolishAcceptsOnlyCompletePresentationEquivalentOutputAndRetainsItIdempotently(){
  String request="1".repeat(32), task="pj"+request, input="选区原文";
  var source=new NoteOriginalService.Content("手动标题",input,null,List.of(),List.of());
  tx.executeWithoutResult(t->originals.freeze("m",new NoteOriginalService.Input(request,"draft",null,null,"polish",source,input,null)));
  db.update("INSERT INTO text_polish_job VALUES(?,?,?)",task,"m",input);
  String ai="  今天保留 300 篇。\r\n\r\n在【小站】发布 *文章*。  ";
  when(text.get("m",task)).thenReturn(new TextPolishJobService.Result(task,"SUCCEEDED","完成",ai,null,null));
  var candidate=new NoteOriginalService.Content("手动标题","未选文字\n\n今天保留 300 篇。\n\n在【小站】发布 \\*文章\\*。\n\n![原图](/api/images/original/raw)",null,List.of(),List.of());
  tx.executeWithoutResult(t->originals.draftResult("m",request,new NoteOriginalService.DraftResult(task,candidate)));
  tx.executeWithoutResult(t->originals.draftResult("m",request,new NoteOriginalService.DraftResult(task,candidate)));
  assertThat(db.queryForObject("SELECT application FROM note_ai_transformation WHERE id=?",String.class,request)).isEqualTo("DRAFT_RESULT");
  for(String changed:List.of("今天保留 30 篇。\n\n在【小站】发布 *文章*。","今天保留 300 篇。","完全不同的正文")){
   var invalid=new NoteOriginalService.Content("手动标题",changed,null,List.of(),List.of());
   assertThatThrownBy(()->tx.executeWithoutResult(t->originals.draftResult("m",request,new NoteOriginalService.DraftResult(task,invalid)))).hasMessageContaining("没有保留");
  }
  when(text.get("m",task)).thenReturn(new TextPolishJobService.Result(task,"RUNNING","生成中",ai,null,null));
  assertThatThrownBy(()->tx.executeWithoutResult(t->originals.draftResult("m",request,new NoteOriginalService.DraftResult(task,candidate)))).hasMessageContaining("未完成");
 }
 @Test void initialAndTwoRoundsRemainExactIncludingWhitespaceRichContentAndReferences() throws Exception {
  String raw="  原始记录  \n\n![用户图](/api/images/user/raw)\n[附件](/api/files/user/download)\n";
  var structure=json.readTree("{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"  原始记录  \"}]}]}");
  var first=new DocDto("n","用户标题","m",null,null,false,List.of(new DocLinkDto("doc","linked")),List.of(),new RichDocContentDto(1,structure,raw),List.of(),"kb-notes",null,1);
  when(knowledge.getDoc("n")).thenReturn(first);
  tx.executeWithoutResult(t->originals.saved(first,"m",true," 用户标题 "));
  String firstId=originals.originals("m","n").initial().id();
  for(int n=0;n<2;n++){
   String request=(n==0?"a":"b").repeat(32);var source=originals.content(first);
   tx.executeWithoutResult(t->originals.freeze("m",new NoteOriginalService.Input(request,"n","n",1,"polish",source,raw,null)));
   tx.executeWithoutResult(t->originals.result("m",request,"job",new NoteOriginalService.Content("新标题","润色正文",null,List.of(),List.of()),2,"APPLIED"));
  }
  var result=originals.originals("m","n");assertThat(result.initial().id()).isEqualTo(firstId);assertThat(result.initial().markdown()).isEqualTo(raw);assertThat(result.initial().title()).isEqualTo(" 用户标题 ");assertThat(result.initial().document()).isEqualTo(structure);assertThat(result.initial().links()).containsExactly(new DocLinkDto("doc","linked"));assertThat(result.before().markdown()).isEqualTo(raw);
 }
 @Test void deletedDocumentCannotReadEvenWithAnAlreadyLoadedDoc() {
  var doc=doc("保留原始记录","标题",1);when(knowledge.getDoc("n")).thenReturn(doc);
  tx.executeWithoutResult(t->originals.saved(doc,"m",true,"标题"));
  db.update("UPDATE doc SET deleted_at=CURRENT_TIMESTAMP WHERE id='n'");
  assertThatThrownBy(()->originals.originals("m","n")).isInstanceOf(NotFoundException.class);
 }
 @Test void sameRequestIsUniqueAndCannotUseDifferentFullSource(){
  when(knowledge.getDoc("n")).thenReturn(doc("保存正文","标题",1));
  String request="c".repeat(32);var input=new NoteOriginalService.Input(request,"n","n",1,"polish",originals.content(doc("草稿正文","标题",1)),"选区",null);
  String first=tx.execute(t->originals.freeze("m",input));String again=tx.execute(t->originals.freeze("m",input));assertThat(again).isEqualTo(first);
  assertThat(db.queryForObject("SELECT COUNT(*) FROM note_source_snapshot",Integer.class)).isEqualTo(1);
  var changed=new NoteOriginalService.Input(request,"n","n",1,"polish",originals.content(doc("新的草稿","标题",1)),"选区",null);
  assertThatThrownBy(()->tx.execute(t->originals.freeze("m",changed))).isInstanceOf(ConflictException.class);
 }
 @Test void originalWriteFailureRollsBackAiWriteAndAppliedAssociation(){
  var doc=doc("记录".repeat(60),"标题",1);when(knowledge.getDoc("n")).thenReturn(doc);
  tx.executeWithoutResult(t->managed.saved(doc,"m","标题",new CreateNoteRequest("标题",List.of(),doc.content(),null,false,false,true)));
  tx.executeWithoutResult(t->managed.advance("n"));
  when(text.get(any(),any())).thenReturn(new TextPolishJobService.Result("job","SUCCEEDED","完成","完整润色",null,null));
  when(knowledge.updateDerivedNote(any(),any())).thenAnswer(c->{db.update("UPDATE doc SET kb_id='changed' WHERE id='n'");return doc("完整润色","标题",2);});
  db.execute("ALTER TABLE note_ai_transformation ADD CONSTRAINT reject_applied CHECK (application <> 'APPLIED')");
  assertThatThrownBy(()->tx.executeWithoutResult(t->managed.advance("n"))).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  assertThat(db.queryForObject("SELECT kb_id FROM doc WHERE id='n'",String.class)).isEqualTo("kb-notes");
  assertThat(db.queryForObject("SELECT application FROM note_ai_transformation",String.class)).isEqualTo("PENDING");
 }
 @Test void legacyNeverPretendsToBeInitialAndOtherMembersCannotReadSnapshot(){
  when(knowledge.getDoc("n")).thenReturn(doc("当前 AI 正文","当前标题",2));
  db.update("INSERT INTO note_polish_version VALUES('n','m','历史留存的来源','历史结果',CURRENT_TIMESTAMP)");
  var result=originals.originals("m","n");assertThat(result.before().sourceType()).isEqualTo("LEGACY_BEFORE_AI");assertThat(result.before().title()).isNull();assertThat(result.initial()).isNull();assertThat(result.explanation()).contains("无法恢复");
  tx.executeWithoutResult(t->originals.archiveLegacy("m","n"));tx.executeWithoutResult(t->originals.archiveLegacy("m","n"));
  assertThat(db.queryForObject("SELECT COUNT(*) FROM note_source_snapshot",Integer.class)).isEqualTo(1);
  String id=db.queryForObject("SELECT id FROM note_source_snapshot",String.class);
  assertThatThrownBy(()->originals.get("other","n",id)).isInstanceOf(NotFoundException.class);
  assertThatThrownBy(()->originals.originals(null,"n")).isInstanceOf(UnauthorizedException.class);
 }
 @Test void stableDraftBindsItsActualPreAiInputAndAdoptionDoesNotMoveOnLaterUserSave(){
  String stable="d".repeat(32),request="e".repeat(32);
  var source=new NoteOriginalService.Content("","  完整用户记录\n\n",null,List.of(),List.of());
  tx.executeWithoutResult(t->originals.freeze("m",new NoteOriginalService.Input(request,stable,null,null,"polish",source,"完整用户记录",null)));
  var output=new NoteOriginalService.Content("标题","AI 正文",null,List.of(),List.of());
  tx.executeWithoutResult(t->originals.result("m",request,request,output,null,"DRAFT_RESULT"));
  db.update("INSERT INTO doc VALUES(?,'m','kb-notes',NULL,NULL)",stable);
  var saved=new DocDto(stable,"标题","m",null,null,false,List.of(),List.of(),new RichDocContentDto(1,null,"AI 正文"),List.of(),"kb-notes",null,1);
  when(knowledge.getDoc(stable)).thenReturn(saved);
  tx.executeWithoutResult(t->{originals.saved(saved,"m",true,"标题");originals.appliedInputs("m",saved,List.of(request));});
  assertThat(originals.originals("m",stable).initial().markdown()).isEqualTo(source.markdown());
  assertThat(originals.adopted("m",saved).polish()).isTrue();
  var later=new DocDto(stable,"标题","m",null,null,false,List.of(),List.of(),new RichDocContentDto(1,null,"用户后来真实编辑"),List.of(),"kb-notes",null,2);
  tx.executeWithoutResult(t->originals.appliedInputs("m",later,List.of(request)));
  assertThat(db.queryForObject("SELECT applied_rev FROM note_ai_transformation WHERE id=?",Integer.class,request)).isEqualTo(1);
  assertThat(originals.adopted("m",later).polish()).isFalse();
 }
 @Test void retryAfterRevisionChangeReusesSourceButNewRequestMustMatchRevision(){
  when(knowledge.getDoc("n")).thenReturn(doc("来源","标题",1));
  var input=new NoteOriginalService.Input("f".repeat(32),"n","n",1,"polish",originals.content(doc("来源","标题",1)),"来源",null);
  String source=tx.execute(t->originals.freeze("m",input));
  when(knowledge.getDoc("n")).thenReturn(doc("新的正文","标题",2));
  String retry=tx.execute(t->originals.freeze("m",input));assertThat(retry).isEqualTo(source);
  var fresh=new NoteOriginalService.Input("0".repeat(32),"n","n",1,"polish",input.content(),input.inputText(),null);
  assertThatThrownBy(()->tx.execute(t->originals.freeze("m",fresh))).isInstanceOf(ConflictException.class);
 }
 @Test void sourceFailurePreventsSubmissionAndAssetsRemainReferencedUntilDocumentPurge(){
  String asset="img1791037956143466";db.update("INSERT INTO image_blob VALUES(?)",asset);
  String raw="![用户图片](/api/images/"+asset+"/raw)";
  tx.executeWithoutResult(t->originals.saved(doc(raw,"标题",1),"m",true,"标题"));
  assertThatThrownBy(()->tx.executeWithoutResult(t->originals.requireAssetUnused("images",asset))).isInstanceOf(ConflictException.class);
  var source=doc("可润色内容","标题",1);when(knowledge.getDoc("n")).thenReturn(source);
  tx.executeWithoutResult(t->managed.saved(source,"m","标题",new CreateNoteRequest("标题",List.of(),source.content(),null,false,false,true)));
  db.execute("ALTER TABLE note_ai_transformation ADD CONSTRAINT reject_pending CHECK(application <> 'PENDING')");
  assertThatThrownBy(()->tx.executeWithoutResult(t->managed.advance("n"))).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  verify(text,never()).submit(any(),any(),any(),any(),any(),any(),any());
  db.update("DELETE FROM doc WHERE id='n'");assertThat(db.queryForObject("SELECT COUNT(*) FROM note_source_asset_ref",Integer.class)).isZero();
 }
}
