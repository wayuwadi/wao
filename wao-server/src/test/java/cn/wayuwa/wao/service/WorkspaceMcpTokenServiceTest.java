package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.domain.WorkspaceMcpTokenEntity;
import cn.wayuwa.wao.mapper.WorkspaceMcpTokenMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WorkspaceMcpTokenServiceTest {
    private final WorkspaceMcpTokenMapper mapper = mock(WorkspaceMcpTokenMapper.class);
    private final WorkspaceMcpTokenService tokens = new WorkspaceMcpTokenService(mapper);

    @Test
    void storesOnlyDigestAndKeepsReadWriteScope() {
        when(mapper.selectCount(any())).thenReturn(0L);
        ArgumentCaptor<WorkspaceMcpTokenEntity> captured = ArgumentCaptor.forClass(WorkspaceMcpTokenEntity.class);

        var created = tokens.create("wd", "本机 Codex", true);

        verify(mapper).insert(captured.capture());
        var row = captured.getValue();
        assertThat(created.secret()).matches("wao_workspace_[A-Za-z0-9_-]{43}");
        assertThat(row.getTokenHash()).matches("[0-9a-f]{64}").doesNotContain(created.secret());
        assertThat(row.getCanWrite()).isTrue();
        assertThat(row.getCanPublishHostedPage()).isFalse();
        assertThat(created.token().tokenPrefix()).isEqualTo(created.secret().substring(0, 18));
        assertThat(created.token().canPublishHostedPage()).isFalse();
        when(mapper.selectOne(any())).thenReturn(row);
        assertThat(tokens.authenticate(created.secret()).memberId()).isEqualTo("wd");
        assertThat(tokens.authenticate(created.secret()).canWrite()).isTrue();
        assertThat(tokens.authenticate(created.secret()).canPublishHostedPage()).isFalse();
        assertThat(tokens.authenticate("wao_mcp_old-memory-token")).isNull();
    }

    @Test
    void persistsHostedPagePermissionOnlyWhenExplicitlyRequested() {
        when(mapper.selectCount(any())).thenReturn(0L);
        ArgumentCaptor<WorkspaceMcpTokenEntity> captured = ArgumentCaptor.forClass(WorkspaceMcpTokenEntity.class);

        var created = tokens.create("wd", "发布机", false, true);

        verify(mapper).insert(captured.capture());
        assertThat(captured.getValue().getCanWrite()).isFalse();
        assertThat(captured.getValue().getCanPublishHostedPage()).isTrue();
        assertThat(created.token().canPublishHostedPage()).isTrue();
        when(mapper.selectOne(any())).thenReturn(captured.getValue());
        var authenticated = tokens.authenticate(created.secret());
        assertThat(authenticated.canWrite()).isFalse();
        assertThat(authenticated.canPublishHostedPage()).isTrue();
    }

    @Test
    void neverRevokesAnotherMembersToken() {
        var row = new WorkspaceMcpTokenEntity(); row.setId("t1"); row.setMemberId("wg");
        when(mapper.selectById("t1")).thenReturn(row);
        assertThatThrownBy(() -> tokens.revoke("wd", "t1"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void verificationUpdatesOnlyMetadataWithOwnerAndActiveConnectionGuards() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), WorkspaceMcpTokenEntity.class);
        ArgumentCaptor<LambdaUpdateWrapper<WorkspaceMcpTokenEntity>> update = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        tokens.recordVerification(new WorkspaceMcpTokenService.AuthenticatedToken("t1", "wd", false), 8);
        verify(mapper).update(org.mockito.ArgumentMatchers.isNull(), update.capture());
        assertThat(update.getValue().getSqlSegment()).contains("id", "member_id", "revoked_at IS NULL");
        assertThat(update.getValue().getParamNameValuePairs().values()).contains("t1", "wd", 8);
        assertThat(update.getValue().getSqlSet()).contains("last_verified_at", "verified_tool_count")
                .doesNotContain("permission_policy", "can_write", "revoked_at", "token_hash");
    }
}
