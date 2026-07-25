package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.common.VaultCryptoService;
import cn.wayuwa.wao.domain.VaultGroupEntity;
import cn.wayuwa.wao.domain.VaultItemEntity;
import cn.wayuwa.wao.dto.CreateVaultItemRequest;
import cn.wayuwa.wao.dto.VaultItemDto;
import cn.wayuwa.wao.mapper.VaultGroupMapper;
import cn.wayuwa.wao.mapper.VaultItemMapper;
import org.junit.jupiter.api.Test;

class VaultServiceTest {

    private static final String KEY =
            Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    private final VaultGroupMapper groupMapper = mock(VaultGroupMapper.class);
    private final VaultItemMapper itemMapper = mock(VaultItemMapper.class);
    private final VaultCryptoService crypto = new VaultCryptoService(KEY);
    private final VaultService service = new VaultService(groupMapper, itemMapper, crypto);

    @Test
    void createItemEncryptsSecretBeforeStoring() {
        VaultGroupEntity group = new VaultGroupEntity();
        group.setId("vg1");
        when(groupMapper.selectById("vg1")).thenReturn(group);

        VaultItemDto dto = service.createItem(
                new CreateVaultItemRequest("vg1", "测试条目", null, "user", "site.example", "", "s3cr3t!"));

        assertThat(dto.hasSecret()).isTrue();
        var captor = org.mockito.ArgumentCaptor.forClass(VaultItemEntity.class);
        verify(itemMapper).insert(captor.capture());
        VaultItemEntity saved = captor.getValue();
        assertThat(saved.getSecretCiphertext()).isNotNull();
        assertThat(new String(saved.getSecretCiphertext(), StandardCharsets.UTF_8)).doesNotContain("s3cr3t!");
    }

    @Test
    void createItemWithoutSecretHasNoSecretFlag() {
        VaultGroupEntity group = new VaultGroupEntity();
        group.setId("vg1");
        when(groupMapper.selectById("vg1")).thenReturn(group);

        VaultItemDto dto = service.createItem(
                new CreateVaultItemRequest("vg1", "测试条目", null, "user", null, null, null));

        assertThat(dto.hasSecret()).isFalse();
    }

    @Test
    void revealSecretReturnsNullWhenNotSet() {
        VaultItemEntity item = new VaultItemEntity();
        item.setId("vi1");
        when(itemMapper.selectById("vi1")).thenReturn(item);

        assertThat(service.revealSecret("vi1")).isNull();
    }

    @Test
    void revealSecretDecryptsRoundTrip() {
        VaultGroupEntity group = new VaultGroupEntity();
        group.setId("vg1");
        when(groupMapper.selectById("vg1")).thenReturn(group);
        VaultItemDto created = service.createItem(
                new CreateVaultItemRequest("vg1", "测试条目", null, "user", null, null, "my-real-secret"));

        var captor = org.mockito.ArgumentCaptor.forClass(VaultItemEntity.class);
        verify(itemMapper).insert(captor.capture());
        VaultItemEntity saved = captor.getValue();
        when(itemMapper.selectById(created.id())).thenReturn(saved);

        assertThat(service.revealSecret(created.id())).isEqualTo("my-real-secret");
    }

    @Test
    void createItemThrowsWhenGroupMissing() {
        when(groupMapper.selectById("nope")).thenReturn(null);

        assertThatThrownBy(() -> service.createItem(
                new CreateVaultItemRequest("nope", "名称", null, null, null, null, null)))
                .isInstanceOf(NotFoundException.class);
    }
}
