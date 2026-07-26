package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.common.VaultCryptoService;
import cn.wayuwa.wao.domain.VaultGroupEntity;
import cn.wayuwa.wao.domain.VaultItemEntity;
import cn.wayuwa.wao.dto.CreateVaultItemRequest;
import cn.wayuwa.wao.dto.PatchVaultGroupRequest;
import cn.wayuwa.wao.dto.VaultItemDto;
import cn.wayuwa.wao.mapper.VaultGroupMapper;
import cn.wayuwa.wao.mapper.VaultItemMapper;
import org.junit.jupiter.api.Test;

class VaultServiceTest {

    private static final String KEY =
            Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
    private static final String OWNER = "wg";
    private static final String OTHER = "wd";

    private final VaultGroupMapper groupMapper = mock(VaultGroupMapper.class);
    private final VaultItemMapper itemMapper = mock(VaultItemMapper.class);
    private final VaultCryptoService crypto = new VaultCryptoService(KEY);
    private final VaultService service = new VaultService(groupMapper, itemMapper, crypto);

    /** 造一个归属给 owner 的分组，并让 mapper 按 id 查得到。 */
    private VaultGroupEntity groupOwnedBy(String id, String owner) {
        VaultGroupEntity group = new VaultGroupEntity();
        group.setId(id);
        group.setName("常用");
        group.setOwnerMemberId(owner);
        when(groupMapper.selectById(id)).thenReturn(group);
        return group;
    }

    @Test
    void createItemEncryptsSecretBeforeStoring() {
        groupOwnedBy("vg1", OWNER);

        VaultItemDto dto = service.createItem(
                new CreateVaultItemRequest("vg1", "测试条目", null, "user", "site.example", "", "s3cr3t!"), OWNER);

        assertThat(dto.hasSecret()).isTrue();
        var captor = org.mockito.ArgumentCaptor.forClass(VaultItemEntity.class);
        verify(itemMapper).insert(captor.capture());
        VaultItemEntity saved = captor.getValue();
        assertThat(saved.getSecretCiphertext()).isNotNull();
        assertThat(new String(saved.getSecretCiphertext(), StandardCharsets.UTF_8)).doesNotContain("s3cr3t!");
    }

    @Test
    void createItemWithoutSecretHasNoSecretFlag() {
        groupOwnedBy("vg1", OWNER);

        VaultItemDto dto = service.createItem(
                new CreateVaultItemRequest("vg1", "测试条目", null, "user", null, null, null), OWNER);

        assertThat(dto.hasSecret()).isFalse();
    }

    @Test
    void revealSecretReturnsNullWhenNotSet() {
        groupOwnedBy("vg1", OWNER);
        VaultItemEntity item = new VaultItemEntity();
        item.setId("vi1");
        item.setGroupId("vg1");
        when(itemMapper.selectById("vi1")).thenReturn(item);

        assertThat(service.revealSecret("vi1", OWNER)).isNull();
    }

    @Test
    void revealSecretDecryptsRoundTrip() {
        groupOwnedBy("vg1", OWNER);
        VaultItemDto created = service.createItem(
                new CreateVaultItemRequest("vg1", "测试条目", null, "user", null, null, "my-real-secret"), OWNER);

        var captor = org.mockito.ArgumentCaptor.forClass(VaultItemEntity.class);
        verify(itemMapper).insert(captor.capture());
        VaultItemEntity saved = captor.getValue();
        when(itemMapper.selectById(created.id())).thenReturn(saved);

        assertThat(service.revealSecret(created.id(), OWNER)).isEqualTo("my-real-secret");
    }

    @Test
    void createItemThrowsWhenGroupMissing() {
        when(groupMapper.selectById("nope")).thenReturn(null);

        assertThatThrownBy(() -> service.createItem(
                new CreateVaultItemRequest("nope", "名称", null, null, null, null, null), OWNER))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void createItemRejectsGroupOwnedBySomeoneElse() {
        groupOwnedBy("vg-other", OTHER);

        assertThatThrownBy(() -> service.createItem(
                new CreateVaultItemRequest("vg-other", "名称", null, null, null, null, null), OWNER))
                .isInstanceOf(NotFoundException.class);
        verify(itemMapper, never()).insert(any(VaultItemEntity.class));
    }

    @Test
    void revealSecretRejectsItemInSomeoneElsesGroup() {
        groupOwnedBy("vg-other", OTHER);
        VaultItemEntity item = new VaultItemEntity();
        item.setId("vi9");
        item.setGroupId("vg-other");
        when(itemMapper.selectById("vi9")).thenReturn(item);

        assertThatThrownBy(() -> service.revealSecret("vi9", OWNER)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void renameGroupRejectsGroupOwnedBySomeoneElse() {
        groupOwnedBy("vg-other", OTHER);

        assertThatThrownBy(() -> service.renameGroup("vg-other", new PatchVaultGroupRequest("改名"), OWNER))
                .isInstanceOf(NotFoundException.class);
        verify(groupMapper, never()).updateById(any(VaultGroupEntity.class));
    }

    @Test
    void reorderGroupsSkipsGroupsOwnedBySomeoneElse() {
        groupOwnedBy("vg-mine", OWNER);
        groupOwnedBy("vg-other", OTHER);

        service.reorderGroups(java.util.List.of("vg-other", "vg-mine"), OWNER);

        var captor = org.mockito.ArgumentCaptor.forClass(VaultGroupEntity.class);
        verify(groupMapper).updateById(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo("vg-mine");
    }

    @Test
    void deleteGroupRefusesTheOwnersDefaultGroup() {
        VaultGroupEntity group = groupOwnedBy("vg-default-wg", OWNER);
        group.setName(VaultService.DEFAULT_GROUP_NAME);

        assertThatThrownBy(() -> service.deleteGroup("vg-default-wg", OWNER))
                .isInstanceOf(IllegalArgumentException.class);
        verify(groupMapper, never()).deleteById(anyString());
    }
}
