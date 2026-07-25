package cn.wayuwa.wao.service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.common.VaultCryptoService;
import cn.wayuwa.wao.domain.VaultGroupEntity;
import cn.wayuwa.wao.domain.VaultItemEntity;
import cn.wayuwa.wao.dto.CreateVaultGroupRequest;
import cn.wayuwa.wao.dto.CreateVaultItemRequest;
import cn.wayuwa.wao.dto.PatchVaultGroupRequest;
import cn.wayuwa.wao.dto.PatchVaultItemRequest;
import cn.wayuwa.wao.dto.VaultGroupDto;
import cn.wayuwa.wao.dto.VaultItemDto;
import cn.wayuwa.wao.mapper.VaultGroupMapper;
import cn.wayuwa.wao.mapper.VaultItemMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 密钥库域服务：分组 / 条目的增删改查，密码字段以 AES-256-GCM 加密后落库。
 *
 * <p>列表 / 详情接口从不返回密码明文，只在 {@link #revealSecret} 中按需解密一次；这是服务端静态加密，
 * 具体信任边界见 {@link VaultCryptoService} 的类注释。
 */
@Service
public class VaultService {

    private final VaultGroupMapper groupMapper;
    private final VaultItemMapper itemMapper;
    private final VaultCryptoService crypto;

    public VaultService(VaultGroupMapper groupMapper, VaultItemMapper itemMapper, VaultCryptoService crypto) {
        this.groupMapper = groupMapper;
        this.itemMapper = itemMapper;
        this.crypto = crypto;
    }

    /** 列出全部分组及其条目（不含密码明文）。 */
    public List<VaultGroupDto> listGroups() {
        List<VaultGroupEntity> groups = groupMapper.selectList(
                new LambdaQueryWrapper<VaultGroupEntity>().orderByAsc(VaultGroupEntity::getOrd));
        List<VaultItemEntity> items = itemMapper.selectList(
                new LambdaQueryWrapper<VaultItemEntity>().orderByAsc(VaultItemEntity::getOrd));
        Map<String, List<VaultItemEntity>> itemsByGroup = items.stream()
                .collect(Collectors.groupingBy(VaultItemEntity::getGroupId));
        return groups.stream()
                .map(g -> new VaultGroupDto(g.getId(), g.getName(),
                        itemsByGroup.getOrDefault(g.getId(), List.of()).stream().map(this::toDto).toList()))
                .toList();
    }

    /** 新建分组。 */
    public VaultGroupDto createGroup(CreateVaultGroupRequest req) {
        VaultGroupEntity e = new VaultGroupEntity();
        e.setId("vg" + System.currentTimeMillis());
        e.setName(req.name().trim());
        e.setOrd(nextOrd(groupMapper.selectCount(null)));
        groupMapper.insert(e);
        return new VaultGroupDto(e.getId(), e.getName(), List.of());
    }

    /** 重命名分组。 */
    public VaultGroupDto renameGroup(String id, PatchVaultGroupRequest req) {
        VaultGroupEntity e = requireGroup(id);
        if (req.name() != null && !req.name().isBlank()) {
            e.setName(req.name().trim());
            groupMapper.updateById(e);
        }
        List<VaultItemDto> items = itemMapper.selectList(new LambdaQueryWrapper<VaultItemEntity>()
                        .eq(VaultItemEntity::getGroupId, id).orderByAsc(VaultItemEntity::getOrd))
                .stream().map(this::toDto).toList();
        return new VaultGroupDto(e.getId(), e.getName(), items);
    }

    /** 删除分组，级联删除其下条目。 */
    @Transactional
    public void deleteGroup(String id) {
        requireGroup(id);
        itemMapper.delete(new LambdaQueryWrapper<VaultItemEntity>().eq(VaultItemEntity::getGroupId, id));
        groupMapper.deleteById(id);
    }

    /** 新建条目；secret 非空时立即加密存储，永不以明文落库。 */
    public VaultItemDto createItem(CreateVaultItemRequest req) {
        requireGroup(req.groupId());
        VaultItemEntity e = new VaultItemEntity();
        e.setId("vi" + System.currentTimeMillis());
        e.setGroupId(req.groupId());
        e.setName(req.name().trim());
        e.setIcon(blankOr(req.icon(), "ri-key-2-line"));
        e.setUserName(req.user());
        e.setSite(req.site());
        e.setNote(req.note());
        applySecret(e, req.secret());
        e.setUpdatedLabel("刚刚");
        e.setOrd(nextOrd(itemMapper.selectCount(
                new LambdaQueryWrapper<VaultItemEntity>().eq(VaultItemEntity::getGroupId, req.groupId()))));
        itemMapper.insert(e);
        return toDto(e);
    }

    /** 局部更新条目。 */
    public VaultItemDto updateItem(String id, PatchVaultItemRequest req) {
        VaultItemEntity e = requireItem(id);
        if (req.name() != null && !req.name().isBlank()) {
            e.setName(req.name().trim());
        }
        if (req.icon() != null && !req.icon().isBlank()) {
            e.setIcon(req.icon());
        }
        if (req.user() != null) {
            e.setUserName(req.user());
        }
        if (req.site() != null) {
            e.setSite(req.site());
        }
        if (req.note() != null) {
            e.setNote(req.note());
        }
        if (req.groupId() != null && !req.groupId().isBlank()) {
            requireGroup(req.groupId());
            e.setGroupId(req.groupId());
        }
        if (req.secret() != null && !req.secret().isBlank()) {
            applySecret(e, req.secret());
        }
        e.setUpdatedLabel("刚刚");
        itemMapper.updateById(e);
        return toDto(e);
    }

    /** 删除条目。 */
    public void deleteItem(String id) {
        requireItem(id);
        itemMapper.deleteById(id);
    }

    /** 解密并返回一次密码明文；条目未设置密码时返回 null（演示种子数据即此情况）。 */
    public String revealSecret(String id) {
        VaultItemEntity e = requireItem(id);
        if (e.getSecretCiphertext() == null || e.getSecretNonce() == null) {
            return null;
        }
        return crypto.decrypt(e.getSecretCiphertext(), e.getSecretNonce());
    }

    private void applySecret(VaultItemEntity e, String secret) {
        if (secret == null || secret.isBlank()) {
            e.setSecretCiphertext(null);
            e.setSecretNonce(null);
            return;
        }
        VaultCryptoService.Encrypted enc = crypto.encrypt(secret);
        e.setSecretCiphertext(enc.ciphertext());
        e.setSecretNonce(enc.nonce());
    }

    private VaultItemDto toDto(VaultItemEntity e) {
        return new VaultItemDto(e.getId(), e.getName(), e.getIcon(), e.getUserName(), e.getSite(), e.getNote(),
                e.getUpdatedLabel(), e.getSecretCiphertext() != null);
    }

    private VaultGroupEntity requireGroup(String id) {
        VaultGroupEntity e = groupMapper.selectById(id);
        if (e == null) {
            throw new NotFoundException("密钥库分组不存在：" + id);
        }
        return e;
    }

    private VaultItemEntity requireItem(String id) {
        VaultItemEntity e = itemMapper.selectById(id);
        if (e == null) {
            throw new NotFoundException("密钥库条目不存在：" + id);
        }
        return e;
    }

    private static String blankOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static int nextOrd(Long existingCount) {
        return (existingCount == null ? 0 : existingCount.intValue()) + 1;
    }
}
