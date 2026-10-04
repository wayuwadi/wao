package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import cn.wayuwa.wao.common.*;
import cn.wayuwa.wao.domain.AiAssistantRunEntity;
import cn.wayuwa.wao.mapper.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Date;
import java.time.LocalDate;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;

/** 用真实 SQL 和事务验证版本、私有图片与迟到结果；模型调用由精确替身隔离。 */
class TianrenVisualServiceTest {
    JdbcTemplate db; TianrenVisualService service; TianrenSettingsService settings;
    AiAssistantRunMapper runs; AiAssistantConversationService conversations; LocalNoteAiService executor;
    ObjectMapper json=new ObjectMapper(); int serial;
    @BeforeEach void prepare() throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        db=new JdbcTemplate(ds);
        db.execute("CREATE TABLE member(id VARCHAR(32) PRIMARY KEY)");db.update("INSERT INTO member VALUES ('m')");
        db.execute("CREATE TABLE tianren_daily_reading(id VARCHAR(48) PRIMARY KEY,member_id VARCHAR(32),conversation_id VARCHAR(48),reading_date DATE)");
        db.update("INSERT INTO tianren_daily_reading VALUES('reading','m','conversation',?)",Date.valueOf(LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))));
        db.execute("CREATE TABLE ai_assistant_run(id VARCHAR(48) PRIMARY KEY,status VARCHAR(32),claim_token VARCHAR(64),response_text TEXT,error_text TEXT,ai_capability_id VARCHAR(48))");
        db.execute("CREATE TABLE replies(content TEXT)");
        String migration=Files.readString(Path.of("src/main/resources/db/migration/V181__tianren_private_visuals.sql"));
        for(String sql:migration.split(";")){if(!sql.isBlank())db.execute(sql.replace("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",""));}
        settings=mock(TianrenSettingsService.class);
        when(settings.get("m")).thenReturn(new TianrenSettingsService.View(3,true,"解读偏好","画面偏好","默认解读","默认配图"));
        runs=mock(AiAssistantRunMapper.class);conversations=mock(AiAssistantConversationService.class);executor=mock(LocalNoteAiService.class);
        var provider=mock(ObjectProvider.class);when(provider.getObject()).thenReturn(executor);
        service=new TianrenVisualService(db,json,settings,runs,conversations,provider,new DataSourceTransactionManager(ds));
        when(runs.completeProviderSucceeded(anyString(),anyString(),anyString())).thenAnswer(a->db.update("UPDATE ai_assistant_run SET status='succeeded',response_text=? WHERE id=? AND claim_token=? AND status='provider_running'",a.getArgument(2),a.getArgument(0),a.getArgument(1)));
        doAnswer(a->{db.update("INSERT INTO replies VALUES(?)",a.getArgument(1,String.class));return null;}).when(conversations).appendAssistantReply(any(),anyString());
        when(conversations.latestAssistantReply(any(),eq("m"))).thenAnswer(a->{var replies=db.queryForList("SELECT content FROM replies",String.class);return replies.isEmpty()?null:replies.get(replies.size()-1);});
        when(executor.enqueue(eq("m"),anyString(),eq(TianrenVisualService.IMAGE_PURPOSE),eq("gpt-6-astra"))).thenAnswer(a->{String id="image"+(++serial);db.update("INSERT INTO ai_assistant_run VALUES(?,'secure_queued',NULL,NULL,NULL,'local')",id);return id;});
    }
    AiAssistantRunEntity textRun(String id) {
        var run=new AiAssistantRunEntity();run.setId(id);run.setMemberId("m");run.setConversationId("conversation");run.setRequestedModel("gpt-6-astra");run.setUsagePurpose("TIANREN");run.setPromptText("固定事实和九章节");
        db.update("INSERT INTO ai_assistant_run VALUES(?,'provider_running','claim',NULL,NULL,'local')",id);
        when(runs.selectById(id)).thenAnswer(a->{run.setStatus(db.queryForObject("SELECT status FROM ai_assistant_run WHERE id=?",String.class,id));run.setResponseText(db.queryForObject("SELECT response_text FROM ai_assistant_run WHERE id=?",String.class,id));return run;});
        service.register(run,"权威卦象，无个人身份");return run;
    }
    String output() throws Exception {
        return json.writeValueAsString(Map.of("readingMarkdown",TianrenVisualService.HEADINGS.stream().map(h->"## "+h+"\n合成文化反思。\n").reduce("",String::concat),"visual",Map.of("title","合成图意","change","留出准备","keep","保持诚意","scene","同一人物先停下观察，再有节制地前行")));
    }
    @Test void malformedTextFailsWhileInvalidBriefKeepsValidMarkdown() throws Exception {
        assertThatThrownBy(()->service.parse("这不是 JSON")).isInstanceOf(IllegalStateException.class);
        var node=json.readTree(output());((com.fasterxml.jackson.databind.node.ObjectNode)node.get("visual")).put("title","超长".repeat(30));
        var parsed=service.parse(node.toString());assertThat(parsed.validBrief()).isFalse();assertThat(parsed.markdown()).contains("## 边界提醒");
    }
    @Test void completionSavesPlainMarkdownAndPersistentPendingWithoutCallingImage() throws Exception {
        var run=textRun("text");assertThat(service.completeText(run,"claim",output())).isOne();
        assertThat(db.queryForObject("SELECT state FROM tianren_visual",String.class)).isEqualTo("PENDING");
        assertThat(db.queryForObject("SELECT content FROM replies",String.class)).startsWith("## 卦象事实").doesNotContain("readingMarkdown");
        verifyNoInteractions(executor);assertThat(service.get("m","reading").state()).isEqualTo("QUEUED");
    }
    @Test void appendFailureRollsBackRunAndBrief() throws Exception {
        var run=textRun("text");doThrow(new IllegalStateException("事务内注入失败")).when(conversations).appendAssistantReply(any(),anyString());
        assertThatThrownBy(()->service.completeText(run,"claim",output())).isInstanceOf(IllegalStateException.class);
        assertThat(db.queryForObject("SELECT status FROM ai_assistant_run",String.class)).isEqualTo("provider_running");
        assertThat(db.queryForObject("SELECT state FROM tianren_visual",String.class)).isEqualTo("WAITING_TEXT");
    }
    @Test void expiredTextClaimDoesNotWriteReplyOrBrief() throws Exception {
        var run=textRun("text");assertThat(service.completeText(run,"old-claim",output())).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM replies",Integer.class)).isZero();
        assertThat(service.get("m","reading").interpretationRunId()).isNull();
    }
    @Test void automaticQueueUsesSnapshotAndDuplicateRequestsDoNotConsumeQuota() throws Exception {
        service.completeText(textRun("text"),"claim",output());service.tick("local");service.tick("local");
        service.start("m","reading",new TianrenVisualService.ImageRequest("text","network-retry"));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM tianren_visual_attempt",Integer.class)).isOne();
        verify(executor,times(1)).enqueue(eq("m"),contains("画面偏好"),eq("TIANREN_IMAGE"),eq("gpt-6-astra"));
    }
    @Test void imageIsPrivateAndOldClaimCannotReplaceIt() throws Exception {
        service.completeText(textRun("text"),"claim",output());service.tick("local");
        var image=new AiAssistantRunEntity();image.setId("image1");image.setMemberId("m");
        db.update("UPDATE ai_assistant_run SET status='provider_running',claim_token='image-claim' WHERE id='image1'");
        var out=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(3,2,BufferedImage.TYPE_INT_RGB),"png",out);byte[] png=out.toByteArray();
        assertThat(service.completeImage(image,"expired",png)).isZero();assertThat(service.completeImage(image,"image-claim",png)).isOne();
        String id=service.get("m","reading").imageAttemptId();assertThat(service.raw("m","reading",id)).isEqualTo(png);
        assertThatThrownBy(()->service.raw("other","reading",id)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(()->service.raw(null,"reading",id)).isInstanceOf(UnauthorizedException.class);
        service.start("m","reading",new TianrenVisualService.ImageRequest("text","replacement"));
        db.update("UPDATE ai_assistant_run SET status='failed' WHERE id='image2'");
        assertThat(service.get("m","reading").state()).isEqualTo("FAILED");assertThat(service.get("m","reading").imageAttemptId()).isEqualTo(id);
        assertThat(service.completeImage(image,"image-claim",png)).isZero();
    }
    @Test void staleInterpretationRequestCannotQueueAndHistoricalRecordIsReadOnly() throws Exception {
        service.completeText(textRun("text"),"claim",output());
        assertThatThrownBy(()->service.start("m","reading",new TianrenVisualService.ImageRequest("old","retry"))).isInstanceOf(ConflictException.class);
        db.update("UPDATE tianren_daily_reading SET reading_date=?",Date.valueOf(LocalDate.now().minusDays(3)));
        assertThatThrownBy(()->service.start("m","reading",new TianrenVisualService.ImageRequest("text","retry"))).isInstanceOf(BadRequestException.class);
        assertThat(service.get("m","reading").title()).isEqualTo("合成图意");
    }
    @Test void invalidBriefCompletesTextButNeverQueuesFabricatedImage() throws Exception {
        var node=json.readTree(output());((com.fasterxml.jackson.databind.node.ObjectNode)node).remove("visual");
        service.completeText(textRun("text"),"claim",node.toString());
        assertThat(service.get("m","reading").state()).isEqualTo("BRIEF_FAILED");verifyNoInteractions(executor);
    }
    @Test void partialBriefCannotReachNullContainingImagePrompt() throws Exception {
        var node=json.readTree(output());((com.fasterxml.jackson.databind.node.ObjectNode)node.get("visual")).remove("title");
        service.completeText(textRun("text"),"claim",node.toString());
        assertThat(service.get("m","reading").retryable()).isFalse();
        assertThatThrownBy(()->service.start("m","reading",new TianrenVisualService.ImageRequest("text","retry"))).isInstanceOf(BadRequestException.class);
    }
    @Test void automaticEnqueueFailureEndsWaitingAndAllowsExplicitRetry() throws Exception {
        service.completeText(textRun("text"),"claim",output());
        when(executor.enqueue(anyString(),anyString(),anyString(),anyString())).thenThrow(new BadRequestException("合成容量不足"));
        service.tick("local");
        assertThat(service.get("m","reading").state()).isEqualTo("FAILED");
        assertThat(service.get("m","reading").retryable()).isTrue();
        assertThat(service.get("m","reading").message()).contains("文字已保留");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM tianren_visual_attempt",Integer.class)).isZero();
    }
    @Test void fakeHeadingsInCodeBlockDoNotCountAsNineSections() throws Exception {
        var node=json.readTree(output());((com.fasterxml.jackson.databind.node.ObjectNode)node).put("readingMarkdown","```\n"+node.get("readingMarkdown").asText()+"\n```");
        assertThatThrownBy(()->service.parse(node.toString())).isInstanceOf(IllegalStateException.class);
    }
    @Test void laterGenericReplyCannotUsePreviousInterpretationImage() throws Exception {
        service.completeText(textRun("text"),"claim",output());db.update("INSERT INTO replies VALUES('合成通用聊天续写')");
        assertThat(service.get("m","reading").interpretationRunId()).isNull();
        assertThatThrownBy(()->service.start("m","reading",new TianrenVisualService.ImageRequest("text","retry"))).isInstanceOf(ConflictException.class);
    }
}
