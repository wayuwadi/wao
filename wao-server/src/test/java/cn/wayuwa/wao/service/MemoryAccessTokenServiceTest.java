package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.domain.MemoryAccessTokenEntity;
import cn.wayuwa.wao.mapper.MemoryAccessTokenMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MemoryAccessTokenServiceTest {

    private final MemoryAccessTokenMapper mapper = mock(MemoryAccessTokenMapper.class);
    private final MemoryAccessTokenService service = new MemoryAccessTokenService(mapper);

    @Test
    void createsHighEntropySecretAndStoresOnlyItsDigest() {
        when(mapper.selectCount(any())).thenReturn(0L);
        ArgumentCaptor<MemoryAccessTokenEntity> inserted = ArgumentCaptor.forClass(MemoryAccessTokenEntity.class);

        var created = service.create("wg", "家里 Windows");

        verify(mapper).insert(inserted.capture());
        MemoryAccessTokenEntity row = inserted.getValue();
        assertThat(created.secret()).matches("wao_mcp_[A-Za-z0-9_-]{43}");
        assertThat(row.getTokenHash()).matches("[0-9a-f]{64}").doesNotContain(created.secret());
        assertThat(row.getTokenPrefix()).isEqualTo(created.secret().substring(0, 16));
        assertThat(created.token().name()).isEqualTo("家里 Windows");
    }

    @Test
    void authenticatesActiveTokenAndRecordsLastUse() {
        when(mapper.selectCount(any())).thenReturn(0L);
        ArgumentCaptor<MemoryAccessTokenEntity> inserted = ArgumentCaptor.forClass(MemoryAccessTokenEntity.class);
        var created = service.create("wd", "办公室 Mac");
        verify(mapper).insert(inserted.capture());
        when(mapper.selectOne(any())).thenReturn(inserted.getValue());

        assertThat(service.authenticate(created.secret())).isEqualTo("wd");
        assertThat(inserted.getValue().getLastUsedAt()).isNotNull();
        verify(mapper).updateById(inserted.getValue());
    }

    @Test
    void refusesToRevokeAnotherMembersToken() {
        MemoryAccessTokenEntity row = new MemoryAccessTokenEntity();
        row.setId("token-1");
        row.setMemberId("wg");
        when(mapper.selectById("token-1")).thenReturn(row);

        assertThatThrownBy(() -> service.revoke("wd", "token-1"))
                .isInstanceOf(NotFoundException.class).hasMessage("记忆令牌不存在");
    }
    @Test
    void archiveGrantIsExplicitWriteOnlyAndBoundToTokenOwner() {
        var grantMapper = mock(cn.wayuwa.wao.mapper.MemoryAccessGrantMapper.class);
        var scoped = new MemoryAccessTokenService(mapper, grantMapper, null, null, null);
        MemoryAccessTokenEntity row = new MemoryAccessTokenEntity();
        row.setId("token-1"); row.setMemberId("wg");
        when(mapper.selectById("token-1")).thenReturn(row);
        var normalized = scoped.saveGrants("wg", "token-1", java.util.List.of(
                new cn.wayuwa.wao.dto.MemoryGrantDto("codex_archive", "another-member", true, true, true)));
        assertThat(normalized).contains(new cn.wayuwa.wao.dto.MemoryGrantDto("codex_archive", "wg", false, false, true));
        assertThat(scoped.saveGrants("wg", "token-1", java.util.List.of()))
                .noneMatch(item -> "codex_archive".equals(item.scopeType()));
    }

}
