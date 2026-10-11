package cn.wayuwa.wao.service;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import cn.wayuwa.wao.common.*;
import cn.wayuwa.wao.dto.*;
import cn.wayuwa.wao.domain.AiAssistantRunEntity;
import cn.wayuwa.wao.mapper.*;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** SQL 状态与真实版本竞态；模型输出仅为明确测试样本。 */
class NoteAiCompanionServiceTest {
    JdbcTemplate db;ObjectMapper json=new ObjectMapper();NoteAiCompanionService service;KnowledgeService knowledge;NoteAiSettingsService settings;DocDto current;
    @BeforeEach void setup() throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE","sa","");db=new JdbcTemplate(ds);
        db.execute("CREATE TABLE member(id VARCHAR(32) PRIMARY KEY)");db.update("INSERT INTO member VALUES('m'),('other')");
        db.execute("CREATE TABLE doc(id VARCHAR(32) PRIMARY KEY,author_id VARCHAR(32),kb_id VARCHAR(32),deleted_at TIMESTAMP,archived_at TIMESTAMP)");db.update("INSERT INTO doc VALUES('n','m','kb-notes',NULL,NULL)");
        db.execute("CREATE TABLE note_polish_version(doc_id VARCHAR(32),member_id VARCHAR(32),updated_at TIMESTAMP)");
        db.execute("CREATE TABLE note_ai_stage_attempt(id CHAR(32),member_id VARCHAR(32),doc_id VARCHAR(32),state VARCHAR(16),reason_code VARCHAR(32),message VARCHAR(500))");
        try(var c=ds.getConnection()){ScriptUtils.executeSqlScript(c,new ClassPathResource("db/migration/V184__note_ai_companion.sql"));}
        settings=new NoteAiSettingsService(db,mock(MemberPreferenceMapper.class),json);knowledge=mock(KnowledgeService.class);var provider=mock(ObjectProvider.class);when(provider.getObject()).thenReturn(knowledge);
        var local=mock(ObjectProvider.class);when(local.getObject()).thenReturn(mock(LocalNoteAiService.class));var tags=mock(NoteTagService.class);when(tags.lockExisting()).thenReturn(List.of("思考","提示词"));
        service=new NoteAiCompanionService(db,json,settings,provider,local,mock(AiAssistantConversationService.class),mock(AiAssistantRunMapper.class),tags);
        current=doc("原稿中的数字是 75%，不是 70%。","手动标题",1);when(knowledge.getDoc("n")).thenAnswer(c->current);
        when(knowledge.updateDerivedNote(eq("n"),any())).thenAnswer(c->{PatchDocRequest p=c.getArgument(1);current=new DocDto("n",p.title()==null?current.title():p.title(),"m",null,null,false,List.of(),List.of(),p.content()==null?current.content():p.content(),List.of(),"kb-notes",null,current.rev()+1,p.tags()==null?current.tags():p.tags());return current;});
    }
    DocDto doc(String text,String title,int rev){return new DocDto("n",title,"m",null,null,false,List.of(),List.of(),new RichDocContentDto(1,null,text),List.of(),"kb-notes",null,rev,List.of("思考"));}
    String capture(String stage,String text){String id=UUID.randomUUID().toString().replace("-","");service.capture(Map.of("id",id,"member_id","m","source_json",settings.write(current),"stage",stage),text);return id;}
    @Test void reviewingAnImageKeepsLegacyParagraphsAndRestoreOnlyRemovesCover(){
        String body="飞书记录第一段。\n\n第二段及原图：![原图](/api/images/original/raw)";
        current=new DocDto("n","临时标题","m",null,null,false,List.of(),List.of(new DocBlockDto(null,null,body,null,null)),null,List.of(),"kb-notes",null,1);
        String image=capture("image","/api/images/new/raw");
        service.action("m",image,"apply",1);
        assertThat(current.content().markdown()).startsWith("![文章总览图](/api/images/new/raw").endsWith(body);
        service.action("m",image,"restore",2);
        assertThat(current.content().markdown()).isEqualTo(body);
    }
    @Test void pendingOutcomeAndExplicitSeenAreIndependent(){String id=capture("polish","数字为 75%，不是 70%。");assertThat(db.queryForObject("SELECT application FROM note_ai_artifact WHERE id=?",String.class,id)).isEqualTo("PENDING");assertThat(service.receipts("m")).hasSize(1);service.action("m",id,"seen",null);assertThat(service.receipts("m")).isEmpty();assertThat(db.queryForObject("SELECT application FROM note_ai_artifact WHERE id=?",String.class,id)).isEqualTo("PENDING");}
    @Test void staleCandidateDoesNotOverwriteEditedBody(){String id=capture("polish","建议文字");current=doc("我已经改成另一篇内容","手动标题",2);assertThatThrownBy(()->service.action("m",id,"apply",2)).isInstanceOf(ConflictException.class);verify(knowledge,never()).updateDerivedNote(any(),any());}
    @Test void applyAndRestorePreserveLaterTitleEdit(){String id=capture("polish","数字为 75%，不是 70%。");service.action("m",id,"apply",1);current=doc(current.content().markdown(),"用户后来改的标题",3);service.action("m",id,"restore",3);assertThat(current.title()).isEqualTo("用户后来改的标题");assertThat(current.content().markdown()).contains("75%","不是 70%");assertThat(current.content().markdown()).startsWith("原稿");}
    @Test void restoreClearsOldReadingProjectionButRetryKeepsNewerProjection(){String id=capture("polish","数字为 75%，不是 70%。");service.action("m",id,"apply",1);db.update("INSERT INTO note_polish_version VALUES('n','m',CURRENT_TIMESTAMP)");service.action("m",id,"restore",2);assertThat(db.queryForObject("SELECT COUNT(*) FROM note_polish_version",Integer.class)).isZero();db.update("INSERT INTO note_polish_version VALUES('n','m',DATEADD('DAY',1,CURRENT_TIMESTAMP))");service.action("m",id,"restore",3);assertThat(db.queryForObject("SELECT COUNT(*) FROM note_polish_version",Integer.class)).isEqualTo(1);}
    @Test void restoreRefusesLaterBodyEdit(){String id=capture("polish","测试值为 75%，不是 70%。");service.action("m",id,"apply",1);current=doc("我在成果上继续写了内容","手动标题",3);assertThatThrownBy(()->service.action("m",id,"restore",3)).isInstanceOf(ConflictException.class);}
    @Test void unchangedResultIsNotUnreadNewWork(){String id=capture("polish",current.content().markdown());assertThat(service.notNeeded(id)).isTrue();assertThat(service.receipts("m")).isEmpty();}
    @Test void polishedWordingIsAdoptedWithoutASecondNumericReview(){assertThat(service.polishedContent(current,"测试值为百分之七十五，不是百分之七十。").markdown()).isEqualTo("测试值为百分之七十五，不是百分之七十。");}
    @Test void changedParagraphStructureUsesTheCompletePolishedMarkdown() throws Exception {
        var tree=json.readTree("{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"原稿\",\"marks\":[{\"type\":\"bold\"}]}]}]}");
        current=new DocDto("n","手选标题","m",null,null,false,List.of(),List.of(),new RichDocContentDto(1,tree,"**原稿**"),List.of(),"kb-notes",null,1);
        String output="## 整理后的标题\n\n第一段。\n\n- 第二段列表";
        var polished=service.polishedContent(current,output);
        assertThat(polished.markdown()).isEqualTo(output);assertThat(polished.document()).isNull();
        assertThat(tree.path("content").get(0).path("content").get(0).path("text").asText()).isEqualTo("原稿");
    }
    @Test void missingImagesStillFailInsteadOfLosingResources(){current=doc("正文\n\n![原图](/api/images/original/raw)","手选标题",1);assertThatThrownBy(()->service.polishedContent(current,"整理后的正文")).isInstanceOf(ConflictException.class).hasMessageContaining("图片");}
    @Test void memberIsolationAndArchiveRevokeHistory(){String id=capture("title","新标题");assertThatThrownBy(()->service.action("other",id,"seen",null)).isInstanceOf(NotFoundException.class);db.update("UPDATE doc SET archived_at=CURRENT_TIMESTAMP WHERE id='n'");assertThatThrownBy(()->service.action("m",id,"seen",null)).isInstanceOf(NotFoundException.class);}
    @Test void suggestionModeIsRetiredEvenForExistingReviewPreferences(){db.update("INSERT INTO note_ai_companion_preference(member_id,result_mode,version) VALUES('m','REVIEW',1)");assertThat(service.preference("m").get("mode")).isEqualTo("AUTO");assertThatThrownBy(()->service.preference("m","REVIEW",1)).isInstanceOf(BadRequestException.class);assertThatThrownBy(()->service.preference("m","AUTO",0)).isInstanceOf(ConflictException.class);assertThat(service.preference("other").get("mode")).isEqualTo("AUTO");}
    @Test void cancelDoesNotPretendRunningProcessStopped(){db.update("INSERT INTO note_ai_stage_attempt(id,member_id,doc_id,state) VALUES('a','m','n','RUNNING'),('b','m','n','PENDING'),('c','other','n','RUNNING')");service.cancel("m","n",1);assertThat(db.queryForObject("SELECT state FROM note_ai_stage_attempt WHERE id='a'",String.class)).isEqualTo("RUNNING");assertThat(db.queryForObject("SELECT cancel_requested FROM note_ai_stage_attempt WHERE id='a'",Boolean.class)).isTrue();assertThat(db.queryForObject("SELECT state FROM note_ai_stage_attempt WHERE id='b'",String.class)).isEqualTo("SKIPPED");assertThat(db.queryForObject("SELECT cancel_requested FROM note_ai_stage_attempt WHERE id='c'",Boolean.class)).isFalse();}
    @Test void richNodesAndImageMetadataAreNotDiscarded() throws Exception {var tree=json.readTree("{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"attrs\":{\"uid\":\"keep-id\"},\"content\":[{\"type\":\"text\",\"text\":\"原文\"}]},{\"type\":\"image\",\"attrs\":{\"src\":\"/api/images/x/raw\",\"width\":\"50%\",\"height\":\"240\"}}]}");current=new DocDto("n","标题","m",null,null,false,List.of(),List.of(),new RichDocContentDto(1,tree,"原文\n\n![图片](/api/images/x/raw)"),List.of(),"kb-notes",null,1);var changed=service.polishedContent(current,"新文\n\n![图片](/api/images/x/raw)");assertThat(changed.document().path("content").get(0).path("attrs").path("uid").asText()).isEqualTo("keep-id");assertThat(changed.document().path("content").get(1)).isEqualTo(tree.path("content").get(1));assertThatThrownBy(()->service.polishedContent(current,"失去了图片")).isInstanceOf(ConflictException.class);}
    @Test void diffCountsAndGeneratedImageDoNotChangeTextDependency(){assertThat(NoteAiCompanionService.changedChars("abc旧稿xyz","abc新稿xyz")).containsExactly(1,1);assertThat(NoteAiCompanionService.textDependency("正文")).isEqualTo(NoteAiCompanionService.textDependency("![文章总览图](/api/images/a/raw \"wao-image-size:50%xauto\")\n\n正文"));}
    @Test void chatRejectsSourceChangeBeforeConsumption(){assertThatThrownBy(()->service.chat("m","n",new NoteAiCompanionService.ChatInput("概括一下",0,"a".repeat(32),List.of()))).isInstanceOf(ConflictException.class);}
    AiAssistantRunEntity chatRun(){var run=new AiAssistantRunEntity();run.setId("chat-run");run.setMemberId("m");run.setUsagePurpose("NOTE_CONVERSATION");db.update("INSERT INTO note_ai_chat_request(id,member_id,doc_id,input_hash,run_id,sources_json) VALUES(?,?,?,?,?,?)","c".repeat(32),"m","n","h".repeat(64),run.getId(),settings.write(List.of(Map.of("id","n","rev",1,"title","手动标题"))));return run;}
    @Test void answerRequiresExactQuoteAndBoundSource(){var run=chatRun();String answer=service.validateChatRun(run,"{\"answer\":\"测试值是 75%，不是 70%。[来源1]\",\"evidence\":[{\"source\":1,\"quote\":\"原稿中的数字是 75%，不是 70%。\"}]}");assertThat(answer).contains("原文依据","75%","不是 70%");assertThatThrownBy(()->service.validateChatRun(run,"{\"answer\":\"收益已实现[来源1]\",\"evidence\":[{\"source\":1,\"quote\":\"收益已实现\"}]}" )).isInstanceOf(BadRequestException.class);assertThatThrownBy(()->service.validateChatRun(run,"{\"answer\":\"回答[来源2]\",\"evidence\":[]}" )).isInstanceOf(BadRequestException.class);}
    @Test void sourceChangesAndRevokedAccessPreventPublication(){var run=chatRun();current=doc("来源已经改变","手动标题",2);assertThatThrownBy(()->service.validateChatRun(run,null)).isInstanceOf(ConflictException.class);db.update("UPDATE doc SET author_id='other' WHERE id='n'");assertThatThrownBy(()->service.validateChatRun(run,null)).isInstanceOf(NotFoundException.class);}
    void transferMessage(){db.execute("CREATE TABLE ai_assistant_conversation(id VARCHAR(32),member_id VARCHAR(32))");db.execute("CREATE TABLE ai_assistant_message(id VARCHAR(32),conversation_id VARCHAR(32),role VARCHAR(16))");db.update("INSERT INTO ai_assistant_conversation VALUES('cv','m'),('foreign-cv','other')");db.update("INSERT INTO ai_assistant_message VALUES('reply','cv','assistant'),('question','cv','user'),('foreign-reply','foreign-cv','assistant')");db.update("INSERT INTO note_ai_conversation(member_id,doc_id,conversation_id,sources_json) VALUES('m','n','cv','[]'),('other','n','foreign-cv','[]')");}
    NoteAiCompanionService.TransferInput transferInput(String message,boolean existing,int rev){return new NoteAiCompanionService.TransferInput(message,existing?"n":"derived-note",existing,rev,"回答草稿",new RichDocContentDto(1,null,"由回答带入，仍待用户确认"),List.of(),List.of());}
    @Test void transferRejectsForeignMessagesAndUserQuestions(){transferMessage();for(String message:List.of("foreign-reply","question","missing"))assertThatThrownBy(()->service.transfer("m",transferInput(message,false,1))).isInstanceOf(NotFoundException.class);verify(knowledge,never()).createNote(anyString(),any(),anyString());}
    @Test void transferPreservesDraftCasAndDerivedOrigin(){transferMessage();assertThatThrownBy(()->service.transfer("m",transferInput("reply",true,0))).isInstanceOf(ConflictException.class);when(knowledge.createNote(eq("derived-note"),any(),eq("m"))).thenAnswer(c->{assertThat(NoteAiManagedService.derived()).isTrue();return current;});service.transfer("m",transferInput("reply",false,1));assertThat(NoteAiManagedService.derived()).isFalse();verify(knowledge).createNote(eq("derived-note"),any(),eq("m"));}
    @Test void tagRestoreKeepsLaterManualTag(){String id=capture("tags","提示词");service.action("m",id,"apply",1);current=new DocDto("n",current.title(),"m",null,null,false,List.of(),List.of(),current.content(),List.of(),"kb-notes",null,3,List.of("思考","提示词","手动新标签"));service.action("m",id,"restore",3);assertThat(current.tags()).containsExactly("思考","手动新标签");}
    @Test void restoreKeepsNewGeneratedCoverAndOriginalRichStructure() throws Exception {var tree=json.readTree("{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"attrs\":{\"uid\":\"p1\"},\"content\":[{\"type\":\"text\",\"text\":\"原稿中的数字是 75%，不是 70%。\"}]}]}");current=new DocDto("n","标题","m",null,null,false,List.of(),List.of(),new RichDocContentDto(1,tree,current.content().markdown()),List.of(),"kb-notes",null,1);String polish=capture("polish","测试值是 75%，不是 70%。");service.action("m",polish,"apply",1);String image=capture("image","/api/images/new/raw");service.action("m",image,"apply",2);service.action("m",polish,"restore",3);assertThat(current.content().markdown()).startsWith("![文章总览图](/api/images/new/raw").contains("原稿中的数字");assertThat(current.content().document().path("content").get(1).path("attrs").path("uid").asText()).isEqualTo("p1");}
}
