package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.wayuwa.wao.common.ConflictException;
import cn.wayuwa.wao.common.UnauthorizedException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class DevelopmentServiceTest {
    JdbcTemplate db;
    DevelopmentService service;
    TransactionTemplate tx;
    @BeforeEach void setup() throws Exception {
        var source=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        db=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        String ddl;
        try(var input=getClass().getResourceAsStream("/db/migration/V190__autonomous_development.sql")){ddl=new String(input.readAllBytes(),StandardCharsets.UTF_8);}
        for(String statement:ddl.split(";"))if(!statement.isBlank())db.execute(statement);
        var permission=mock(NotificationSettingsService.class);
        when(permission.canManageSystem("owner")).thenReturn(true);
        when(permission.canManageSystem("other-admin")).thenReturn(true);
        service=new DevelopmentService(db,new ObjectMapper(),permission);
    }
    void enable(int parallel) {var p=service.policy("owner");service.savePolicy("owner",new DevelopmentService.Policy(true,p.version(),24,parallel));}
    DevelopmentService.Lease claim() {return tx.execute(s->service.claim("owner"));}
    @Test void openingIsOffAndSameFeatureIsDeduplicatedAndMemberIsolated() {
        assertThat(service.overview("owner").policy().enabled()).isFalse();
        var a=service.create("owner","notes");
        assertThat(service.create("owner","notes").id()).isEqualTo(a.id());
        assertThat(service.overview("other-admin").runs()).isEmpty();
        assertThatThrownBy(()->service.get("other-admin",a.id())).hasMessageContaining("不存在");
        assertThat(service.overview("guest").canManage()).isFalse();
        assertThatThrownBy(()->service.create("guest","home")).isInstanceOf(UnauthorizedException.class);
        assertThat(claim()).isNull();
    }
    @Test void policyCasDoesNotOverwriteNewerSettings() {
        var original=service.policy("owner");enable(1);
        assertThatThrownBy(()->service.savePolicy("owner",original)).isInstanceOf(ConflictException.class);
        assertThat(service.policy("owner").enabled()).isTrue();
    }
    @Test void explicitlyConfiguredMemberDoesNotNeedOrganizationAdminAndDoesNotGrantOthers() {
        var permission=mock(NotificationSettingsService.class);
        var scoped=new DevelopmentService(db,new ObjectMapper(),permission,"owner");
        assertThat(scoped.overview("owner").canManage()).isTrue();
        assertThat(scoped.overview("other-admin").canManage()).isFalse();
        assertThat(scoped.overview("other-admin").runs()).isEmpty();
    }
    @Test void leaseLimitAndFencingPreserveInterruptedWork() {
        enable(1);service.create("owner","notes");service.create("owner","home");
        var first=claim();assertThat(first).isNotNull();assertThat(claim()).isNull();
        db.update("UPDATE development_run SET lease_until=? WHERE id=?",LocalDateTime.now().minusMinutes(2),first.id());
        assertThat(service.renew(first)).isFalse();
        assertThatThrownBy(()->service.progress(first,"RELEASED","假成功",null,null,null,null,null)).isInstanceOf(ConflictException.class);
        var second=claim();assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(service.get("owner",first.id()).status()).isEqualTo("FAILED");
    }
    @Test void explicitDecisionContinuesExactlyOnceAndRejectsWrongRevision() {
        enable(1);var run=service.create("owner","notes");var lease=claim();
        tx.executeWithoutResult(s->service.requestDecision(lease,"采用方案？","需要本人决定"));
        var decision=service.get("owner",run.id()).decision();
        assertThat(service.renew(lease)).isFalse();
        assertThatThrownBy(()->tx.execute(s->service.decide("other-admin",decision.id(),decision.revision(),"approve","确认"))).isInstanceOf(ConflictException.class);
        assertThatThrownBy(()->tx.execute(s->service.decide("owner",decision.id(),decision.revision()-1,"approve","确认"))).isInstanceOf(ConflictException.class);
        var approved=tx.execute(s->service.decide("owner",decision.id(),decision.revision(),"approve","确认"));
        assertThat(approved.status()).isEqualTo("QUEUED");
        var duplicate=tx.execute(s->service.decide("owner",decision.id(),decision.revision(),"reject","退回"));
        assertThat(duplicate.version()).isEqualTo(approved.version());
        assertThat(duplicate.decision().status()).isEqualTo("APPROVED");
    }
    @Test void changedRunRollsBackDecisionSoNoApprovalLeaksIntoNewPlan() {
        enable(1);var run=service.create("owner","home");var lease=claim();
        tx.executeWithoutResult(s->service.requestDecision(lease,"采用？","原因"));
        var d=service.get("owner",run.id()).decision();
        db.update("UPDATE development_run SET version=version+1 WHERE id=?",run.id());
        assertThatThrownBy(()->tx.execute(s->service.decide("owner",d.id(),d.revision(),"approve","确认"))).isInstanceOf(ConflictException.class);
        assertThat(service.get("owner",run.id()).decision().status()).isEqualTo("PENDING");
    }
    @Test void rejectionRetryReplansInsteadOfReusingRejectedPlan() throws Exception {
        enable(1);var run=service.create("owner","home");var lease=claim();
        service.progress(lease,"ANALYZING","方案",new ObjectMapper().readTree("{\"goal\":\"旧方案\",\"title\":\"旧标题\"}"),null,null,null,null);
        tx.executeWithoutResult(s->service.requestDecision(lease,"采用？","原因"));var d=service.get("owner",run.id()).decision();
        var rejected=tx.execute(s->service.decide("owner",d.id(),d.revision(),"reject","退回：调整目标"));
        var retried=service.retry("owner",run.id(),rejected.version());assertThat(retried.plan()).isNull();
        assertThat(retried.status()).isEqualTo("QUEUED");assertThat(retried.decision().reply()).contains("调整目标");
    }
    @Test void deliveryIsClaimedOnceAndBoundToExactMessage() {
        enable(1);service.create("owner","home");var lease=claim();
        tx.executeWithoutResult(s->service.requestDecision(lease,"采用？","原因"));
        var d=service.get("owner",lease.id()).decision();
        assertThat(service.delivering(d.id(),"app","open")).isTrue();
        assertThat(service.delivering(d.id(),"app","open")).isFalse();
        service.delivered(d.id(),"message");
        assertThat(service.decisionMessage(d.id(),"message","app","wrong")).isNull();
        assertThat(service.decisionMessage(d.id(),"message","app","open")).isNotNull();
        assertThat(service.pendingDecisions()).isEmpty();
    }
    @Test void offlineDoesNotScheduleAndRealHeartbeatEnablesRotation() {
        enable(1);tx.executeWithoutResult(s->service.schedule("owner"));
        assertThat(service.overview("owner").runs()).isEmpty();
        service.heartbeat("owner","instance","已连接");tx.executeWithoutResult(s->service.schedule("owner"));
        assertThat(service.overview("owner").runs()).hasSize(1);
        tx.executeWithoutResult(s->service.schedule("owner"));assertThat(service.overview("owner").runs()).hasSize(1);
    }
    @Test void auditOnlySkipsSavedPlanAndFormalWorkerCanContinueIt() throws Exception {
        enable(1);service.create("owner","home");var first=tx.execute(s->service.claim("owner",true));
        service.progress(first,"ANALYZING","已审视",new ObjectMapper().readTree("{\"goal\":\"真实改进\",\"title\":\"真实问题\"}"),null,null,null,null);
        service.deferAfterAudit(first);DevelopmentService.Lease noFurtherAudit=tx.execute(s->service.claim("owner",true));assertThat(noFurtherAudit).isNull();
        service.create("owner","notes");var next=tx.execute(s->service.claim("owner",true));assertThat(next.id()).isNotEqualTo(first.id());
        service.finish(next,"NO_CHANGE","已审视",null,null);
        assertThat(claim().id()).isEqualTo(first.id());
    }
    @Test void expiredDecisionBecomesRetryableAndOldReplyRemainsRejected() {
        enable(1);var run=service.create("owner","home");var lease=claim();
        tx.executeWithoutResult(s->service.requestDecision(lease,"采用？","原因"));var d=service.get("owner",run.id()).decision();
        db.update("UPDATE development_decision SET expires_at=? WHERE id=?",LocalDateTime.now().minusMinutes(1),d.id());
        tx.execute(s->service.overview("owner"));var paused=service.get("owner",run.id());
        assertThat(paused.status()).isEqualTo("PAUSED");assertThat(paused.decision().status()).isEqualTo("EXPIRED");
        assertThatThrownBy(()->tx.execute(s->service.decide("owner",d.id(),d.revision(),"approve","确认"))).isInstanceOf(ConflictException.class);
        assertThat(service.retry("owner",run.id(),paused.version()).status()).isEqualTo("QUEUED");
    }
}
