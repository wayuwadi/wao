package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.ConflictException;
import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.dto.NavigationLayoutDto;
import cn.wayuwa.wao.dto.NavigationLayoutDto.Snapshot;
import cn.wayuwa.wao.mapper.MemberPreferenceMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 成员导航布局以单一快照原子保存，保留跨设备冲突而不静默覆盖。 */
@Service
public class NavigationLayoutService {
    public static final String PREFERENCE_KEY = "navigation-layout-v1";
    private final MemberPreferenceMapper mapper;
    private final ObjectMapper json;

    public NavigationLayoutService(MemberPreferenceMapper mapper, ObjectMapper json) {
        this.mapper = mapper;
        this.json = json;
    }

    /** 读取当前成员布局；空记录由前端无损继承原导航，损坏记录不得冒充空值。 */
    public Snapshot get(String memberId) {
        requireMember(memberId);
        return decode(mapper.find(memberId, PREFERENCE_KEY));
    }

    /** 比较客户端版本并原子写入全部布局，跨设备并发修改返回冲突。 */
    public Snapshot save(String memberId, NavigationLayoutDto.Save request) {
        requireMember(memberId);
        if (request == null || request.expectedRevision() == null) {
            throw new BadRequestException("缺少布局版本，请重新读取后再保存");
        }
        validate(request.layout());
        String previous = mapper.find(memberId, PREFERENCE_KEY);
        Snapshot current = decode(previous);
        // 已落库但响应丢失时，同内容重试可以安全返回当前版本。
        if (request.layout().equals(current.layout())) return current;
        if (!current.revision().equals(request.expectedRevision())) {
            throw new ConflictException("另一设备已修改导航，草稿已保留，请读取最新布局后再调整");
        }
        Snapshot next = new Snapshot(UUID.randomUUID().toString(), request.layout());
        final String serialized;
        try {
            serialized = json.writeValueAsString(next);
        } catch (JsonProcessingException exception) {
            throw new BadRequestException("导航布局无法保存");
        }
        if (serialized.getBytes(StandardCharsets.UTF_8).length > 60_000) {
            throw new BadRequestException("导航布局过大，请减少自定义入口或名称长度");
        }
        int changed = previous == null
                ? mapper.insertIfAbsent(memberId, PREFERENCE_KEY, serialized)
                : mapper.compareAndSet(memberId, PREFERENCE_KEY, previous, serialized);
        if (changed != 1) throw new ConflictException("导航已在另一设备更新，请读取最新布局后再调整");
        return next;
    }

    private Snapshot decode(String value) {
        if (value == null) return new Snapshot("none", null);
        try {
            Snapshot snapshot = json.readValue(value, Snapshot.class);
            if (snapshot == null || snapshot.revision() == null || snapshot.revision().isBlank()) {
                throw new IllegalArgumentException();
            }
            validate(snapshot.layout());
            return snapshot;
        } catch (JsonProcessingException | IllegalArgumentException | BadRequestException exception) {
            throw new ConflictException("已保存的导航布局无法读取，原记录仍保留，请联系管理员恢复");
        }
    }

    private void requireMember(String memberId) {
        if (memberId == null || memberId.isBlank()) throw new UnauthorizedException("请登录后整理个人导航");
    }

    private void validate(NavigationLayoutDto layout) {
        if (layout == null || !Integer.valueOf(1).equals(layout.schemaVersion())
                || layout.groups() == null || layout.groups().isEmpty() || layout.groups().size() > 30
                || layout.items() == null || layout.items().size() > 200 || layout.locked() == null) {
            throw new BadRequestException("导航布局格式不正确");
        }
        Set<String> groups = new HashSet<>();
        for (var group : layout.groups()) {
            if (group == null || !identifier(group.id()) || !groups.add(group.id())
                    || group.label() == null || group.label().length() > 24
                    || (!"ROOT".equals(group.id()) && group.label().isBlank())) {
                throw new BadRequestException("导航分组名称或标识不正确");
            }
        }
        if (!groups.contains("ROOT")) throw new BadRequestException("导航必须保留顶层分组");
        Set<String> items = new HashSet<>();
        for (var item : layout.items()) {
            if (item == null || !identifier(item.id()) || !items.add(item.id())
                    || item.label() == null || item.label().isBlank() || item.label().length() > 24
                    || item.icon() == null || !item.icon().matches("ri-[a-z0-9-]{1,64}")
                    || !groups.contains(item.group()) || item.order() == null
                    || Math.abs((long) item.order()) > 1_000_000 || item.visible() == null
                    || item.openMode() == null || !Set.of("TAB", "NEW_PAGE").contains(item.openMode())
                    || item.placement() == null || !Set.of("MENU", "TOP", "DOCK").contains(item.placement())) {
                throw new BadRequestException("菜单名称、位置、分组或打开方式不正确");
            }
        }
    }

    private boolean identifier(String id) {
        return id != null && id.matches("[A-Za-z0-9_-]+(?::[A-Za-z0-9_-]+)?") && id.length() <= 80;
    }
}
