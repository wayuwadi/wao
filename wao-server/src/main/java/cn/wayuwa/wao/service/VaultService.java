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
 * <p>密码库归属到人：每个分组都记 {@code owner_member_id}，所有读写都只在当前成员自己的分组里
 * 生效，别人的分组既看不到也点不到（见 ADR-024）。条目的归属跟随所在分组，不再单独记一列。
 *
 * <p>列表 / 详情接口从不返回密码明文，只在 {@link #revealSecret} 中按需解密一次；这是服务端静态加密，
 * 具体信任边界见 {@link VaultCryptoService} 的类注释。
 */
@Service
public class VaultService {

    /** 每个成员的默认分组名；同名分组视为该成员的默认分组，不允许删除。 */
    public static final String DEFAULT_GROUP_NAME = "未分组";

    private final VaultGroupMapper groupMapper;
    private final VaultItemMapper itemMapper;
    private final VaultCryptoService crypto;

    public VaultService(VaultGroupMapper groupMapper, VaultItemMapper itemMapper, VaultCryptoService crypto) {
        this.groupMapper = groupMapper;
        this.itemMapper = itemMapper;
        this.crypto = crypto;
    }

    /**
     * 列出当前成员自己的分组及其条目（不含密码明文）。
     *
     * @param ownerId 当前登录成员 id
     * @return 该成员的分组列表；第一次访问时会自动带上一个空的「未分组」
     */
    public List<VaultGroupDto> listGroups(String ownerId) {
        ensureDefaultGroup(ownerId);
        List<VaultGroupEntity> groups = groupMapper.selectList(ownedGroups(ownerId)
                .orderByAsc(VaultGroupEntity::getOrd));
        if (groups.isEmpty()) return List.of();
        List<String> groupIds = groups.stream().map(VaultGroupEntity::getId).toList();
        Map<String, List<VaultItemEntity>> itemsByGroup = itemMapper.selectList(
                        new LambdaQueryWrapper<VaultItemEntity>()
                                .in(VaultItemEntity::getGroupId, groupIds)
                                .orderByAsc(VaultItemEntity::getOrd))
                .stream().collect(Collectors.groupingBy(VaultItemEntity::getGroupId));
        return groups.stream()
                .map(g -> new VaultGroupDto(g.getId(), g.getName(),
                        itemsByGroup.getOrDefault(g.getId(), List.of()).stream().map(this::toDto).toList()))
                .toList();
    }

    /**
     * 新建分组，归属到当前成员。
     *
     * @param req     分组名
     * @param ownerId 当前登录成员 id
     * @return 新建的分组
     */
    public VaultGroupDto createGroup(CreateVaultGroupRequest req, String ownerId) {
        VaultGroupEntity e = new VaultGroupEntity();
        e.setId("vg" + System.currentTimeMillis());
        e.setName(req.name().trim());
        e.setOwnerMemberId(ownerId);
        e.setOrd(nextOrd(groupMapper.selectCount(ownedGroups(ownerId))));
        groupMapper.insert(e);
        return new VaultGroupDto(e.getId(), e.getName(), List.of());
    }

    /**
     * 持久化分组拖拽后的顺序；不属于当前成员的 id 被忽略，避免用他人分组探测存在性。
     *
     * @param ids     期望顺序的分组 id
     * @param ownerId 当前登录成员 id
     */
    @Transactional
    public void reorderGroups(List<String> ids, String ownerId) {
        if (ids == null) return;
        int ord = 1;
        for (String id : ids) {
            VaultGroupEntity group = groupMapper.selectById(id);
            if (group != null && owns(group, ownerId)) {
                group.setOrd(ord++);
                groupMapper.updateById(group);
            }
        }
    }

    /**
     * 重命名分组。
     *
     * @param id      分组 id
     * @param req     新分组名
     * @param ownerId 当前登录成员 id
     * @return 重命名后的分组（含条目）
     */
    public VaultGroupDto renameGroup(String id, PatchVaultGroupRequest req, String ownerId) {
        VaultGroupEntity e = requireGroup(id, ownerId);
        if (req.name() != null && !req.name().isBlank()) {
            e.setName(req.name().trim());
            groupMapper.updateById(e);
        }
        List<VaultItemDto> items = itemMapper.selectList(new LambdaQueryWrapper<VaultItemEntity>()
                        .eq(VaultItemEntity::getGroupId, id).orderByAsc(VaultItemEntity::getOrd))
                .stream().map(this::toDto).toList();
        return new VaultGroupDto(e.getId(), e.getName(), items);
    }

    /**
     * 删除分组，级联删除其下条目。
     *
     * @param id      分组 id
     * @param ownerId 当前登录成员 id
     */
    @Transactional
    public void deleteGroup(String id, String ownerId) {
        VaultGroupEntity group = requireGroup(id, ownerId);
        if (DEFAULT_GROUP_NAME.equals(group.getName())) {
            throw new IllegalArgumentException("默认分组不能删除");
        }
        itemMapper.delete(new LambdaQueryWrapper<VaultItemEntity>().eq(VaultItemEntity::getGroupId, id));
        groupMapper.deleteById(id);
    }

    /**
     * 新建条目；secret 非空时立即加密存储，永不以明文落库。
     *
     * @param req     条目内容
     * @param ownerId 当前登录成员 id
     * @return 新建的条目（不含密码明文）
     */
    public VaultItemDto createItem(CreateVaultItemRequest req, String ownerId) {
        String groupId = req.groupId() == null || req.groupId().isBlank()
                ? ensureDefaultGroup(ownerId).getId() : req.groupId();
        requireGroup(groupId, ownerId);
        VaultItemEntity e = new VaultItemEntity();
        e.setId("vi" + System.currentTimeMillis());
        e.setGroupId(groupId);
        e.setName(req.name().trim());
        e.setIcon(blankOr(req.icon(), "ri-key-2-line"));
        e.setUserName(req.user());
        e.setSite(req.site());
        e.setNote(req.note());
        applySecret(e, req.secret());
        e.setUpdatedLabel("刚刚");
        e.setOrd(nextOrd(itemMapper.selectCount(
                new LambdaQueryWrapper<VaultItemEntity>().eq(VaultItemEntity::getGroupId, groupId))));
        itemMapper.insert(e);
        return toDto(e);
    }

    /**
     * 局部更新条目。
     *
     * @param id      条目 id
     * @param req     要更新的字段
     * @param ownerId 当前登录成员 id
     * @return 更新后的条目（不含密码明文）
     */
    public VaultItemDto updateItem(String id, PatchVaultItemRequest req, String ownerId) {
        VaultItemEntity e = requireItem(id, ownerId);
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
            requireGroup(req.groupId(), ownerId);
            e.setGroupId(req.groupId());
        }
        if (req.secret() != null && !req.secret().isBlank()) {
            applySecret(e, req.secret());
        }
        e.setUpdatedLabel("刚刚");
        itemMapper.updateById(e);
        return toDto(e);
    }

    /**
     * 删除条目。
     *
     * @param id      条目 id
     * @param ownerId 当前登录成员 id
     */
    public void deleteItem(String id, String ownerId) {
        requireItem(id, ownerId);
        itemMapper.deleteById(id);
    }

    /**
     * 解密并返回一次密码明文；条目未设置密码时返回 null（演示种子数据即此情况）。
     *
     * @param id      条目 id
     * @param ownerId 当前登录成员 id
     * @return 密码明文，未设置时为 null
     */
    public String revealSecret(String id, String ownerId) {
        VaultItemEntity e = requireItem(id, ownerId);
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

    /**
     * 取一个属于当前成员的分组。
     *
     * <p>别人的分组一律按「不存在」处理，而不是「无权访问」：后者等于确认了这个 id 真实存在。
     */
    private VaultGroupEntity requireGroup(String id, String ownerId) {
        VaultGroupEntity e = groupMapper.selectById(id);
        if (e == null || !owns(e, ownerId)) {
            throw new NotFoundException("密钥库分组不存在：" + id);
        }
        return e;
    }

    private VaultItemEntity requireItem(String id, String ownerId) {
        VaultItemEntity e = itemMapper.selectById(id);
        if (e == null) {
            throw new NotFoundException("密钥库条目不存在：" + id);
        }
        VaultGroupEntity group = groupMapper.selectById(e.getGroupId());
        if (group == null || !owns(group, ownerId)) {
            throw new NotFoundException("密钥库条目不存在：" + id);
        }
        return e;
    }

    private static boolean owns(VaultGroupEntity group, String ownerId) {
        return ownerId != null && ownerId.equals(group.getOwnerMemberId());
    }

    private LambdaQueryWrapper<VaultGroupEntity> ownedGroups(String ownerId) {
        return new LambdaQueryWrapper<VaultGroupEntity>().eq(VaultGroupEntity::getOwnerMemberId, ownerId);
    }

    /** 当前成员的「未分组」，没有就地建一个——每个人都得有个不用起名就能丢东西的地方。 */
    private VaultGroupEntity ensureDefaultGroup(String ownerId) {
        VaultGroupEntity existing = groupMapper.selectList(ownedGroups(ownerId)
                        .eq(VaultGroupEntity::getName, DEFAULT_GROUP_NAME).orderByAsc(VaultGroupEntity::getOrd))
                .stream().findFirst().orElse(null);
        if (existing != null) return existing;
        VaultGroupEntity group = new VaultGroupEntity();
        group.setId("vg-default-" + ownerId);
        group.setName(DEFAULT_GROUP_NAME);
        group.setOwnerMemberId(ownerId);
        group.setOrd(0);
        groupMapper.insert(group);
        return group;
    }

    private static String blankOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static int nextOrd(Long existingCount) {
        return (existingCount == null ? 0 : existingCount.intValue()) + 1;
    }
}
