package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.wayuwa.wao.common.ConflictException;
import cn.wayuwa.wao.dto.SavePersonalPrincipleRequest;
import cn.wayuwa.wao.mapper.MemberMapper;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/** 连接已有业务库的事务内验收；所有试验记录在测试结束时回滚。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver")
@ActiveProfiles("local-cloud")
@EnabledIfEnvironmentVariable(named = "WAO_REAL_DB_ACCEPTANCE", matches = "true")
class PersonalPrincipleRealDatabaseTest {
    @Autowired private PersonalPrincipleService service;
    @Autowired private MemberMapper members;

    @Test
    @Transactional
    void invitesTwoRealMembersWithoutSharingTheirPrivateCheckins() {
        List<String> ids = members.directory().stream().map(member -> member.getId()).limit(2).toList();
        assertThat(ids).hasSize(2);
        String owner = ids.get(0);
        String invited = ids.get(1);
        SavePersonalPrincipleRequest request = new SavePersonalPrincipleRequest(
                "本地验收：睡前收起手机", "MUST", ids,
                "每天睡前", "把手机放在书桌", "紧急联络除外", LocalTime.of(22, 30), "MANUAL", false, null);

        var created = service.create(owner, request);
        assertThat(created.targets()).hasSize(2);
        assertThat(service.list(invited, LocalDate.now(ZoneId.of("Asia/Shanghai"))).stream()
                .filter(item -> item.id().equals(created.id())).findFirst().orElseThrow().myConsent()).isEqualTo("PENDING");

        service.consent(created.id(), invited, "ACCEPTED");
        var recorded = service.checkin(created.id(), invited, LocalDate.now(ZoneId.of("Asia/Shanghai")), "DONE", "本人已执行");
        assertThat(recorded.myCheckin().note()).isEqualTo("本人已执行");
        var ownerView = service.list(owner, LocalDate.now(ZoneId.of("Asia/Shanghai"))).stream()
                .filter(item -> item.id().equals(created.id())).findFirst().orElseThrow();
        assertThat(ownerView.myCheckin()).isNull();

        SavePersonalPrincipleRequest stale = new SavePersonalPrincipleRequest(request.title(), request.category(),
                request.targetMemberIds(), request.triggerText(), request.desiredAction(), request.exceptionText(),
                request.reminderTime(), request.evidenceSource(), false, 0);
        assertThatThrownBy(() -> service.update(created.id(), owner, stale)).isInstanceOf(ConflictException.class);
        assertThat(service.revisions(created.id(), invited)).hasSize(1);
    }
}
