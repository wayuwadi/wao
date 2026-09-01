package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.domain.AppearancePhotoEntity;
import cn.wayuwa.wao.domain.AppearanceWorkspaceEntity;
import cn.wayuwa.wao.dto.AppearancePhotoDto;
import cn.wayuwa.wao.dto.AppearanceWorkspaceDto;
import cn.wayuwa.wao.dto.AppearanceWorkspaceDto.Garment;
import cn.wayuwa.wao.dto.AppearanceWorkspaceDto.Note;
import cn.wayuwa.wao.dto.AppearanceWorkspaceDto.Outfit;
import cn.wayuwa.wao.dto.AppearanceWorkspaceDto.Plan;
import cn.wayuwa.wao.dto.AppearanceWorkspaceDto.Profile;
import cn.wayuwa.wao.dto.AppearanceWorkspaceDto.WearLog;
import cn.wayuwa.wao.mapper.AppearancePhotoMapper;
import cn.wayuwa.wao.mapper.AppearanceWorkspaceMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/** 成员私有形象工作区、真实穿着统计和实拍原件。 */
@Service
public class AppearanceService {
    public static final long MAX_PHOTO_BYTES = 10L * 1024 * 1024;
    static final String DEFAULT_PRINCIPLES = "清爽、干净、舒服；不追求名牌，追求整体风格搭配和谐。多比贵好，合适比喜欢更好。达到 80 分的保持，只有 60 分或 30 分的就大胆改变。";
    private static final int MAX_JSON_LENGTH = 2_000_000;
    private static final Set<String> PHOTO_TYPES = Set.of("image/jpeg", "image/png", "image/webp", "image/gif");
    private static final Set<String> GARMENT_STATUSES = Set.of("WARDROBE", "CLEANING", "IDLE", "DONATED");
    private static final Set<String> PLAN_TYPES = Set.of("EVENT", "CLEANING", "STORAGE", "PURCHASE");
    private static final Set<String> PLAN_STATUSES = Set.of("OPEN", "DONE");

    private final AppearanceWorkspaceMapper workspaces;
    private final AppearancePhotoMapper photos;
    private final ObjectMapper json;

    public AppearanceService(AppearanceWorkspaceMapper workspaces, AppearancePhotoMapper photos,
            ObjectMapper json) {
        this.workspaces = workspaces;
        this.photos = photos;
        this.json = json;
    }

    /** 读取当前成员的真实工作区；首次使用返回空集合，不写入演示记录。 */
    public AppearanceWorkspaceDto workspace(String memberId) {
        requireMember(memberId);
        AppearanceWorkspaceEntity stored = workspaces.selectById(memberId);
        if (stored == null) return empty(null);
        try {
            AppearanceWorkspaceDto parsed = json.readValue(stored.getContentJson(), AppearanceWorkspaceDto.class);
            return normalize(parsed, stored.getUpdatedAt());
        } catch (JsonProcessingException exception) {
            throw new BadRequestException("形象管理数据无法读取，请联系管理员修复后再编辑");
        }
    }

    /** 校验并全量保存工作区；穿着次数和最后穿着日期始终由真实记录重算。 */
    @Transactional
    public AppearanceWorkspaceDto save(String memberId, AppearanceWorkspaceDto requested) {
        requireMember(memberId);
        if (requested == null) throw new BadRequestException("形象管理内容不能为空");
        AppearanceWorkspaceDto normalized = normalize(requested, LocalDateTime.now());
        validate(normalized);
        try {
            String content = json.writeValueAsString(normalized);
            if (content.length() > MAX_JSON_LENGTH) throw new BadRequestException("形象管理内容过多，请精简笔记后重试");
            workspaces.upsert(memberId, content);
            AppearanceWorkspaceEntity saved = workspaces.selectById(memberId);
            LocalDateTime updatedAt = saved == null ? normalized.updatedAt() : saved.getUpdatedAt();
            return new AppearanceWorkspaceDto(normalized.garments(), normalized.outfits(), normalized.profile(),
                    normalized.plans(), normalized.wearLogs(), normalized.notes(), normalized.principles(), updatedAt);
        } catch (JsonProcessingException exception) {
            throw new BadRequestException("形象管理内容无法保存，请检查输入后重试");
        }
    }

    /** 上传一张当前成员私有的实拍图。 */
    @Transactional
    public AppearancePhotoDto uploadPhoto(String memberId, MultipartFile file) {
        requireMember(memberId);
        if (file == null || file.isEmpty()) throw new BadRequestException("请选择要上传的实拍图");
        if (file.getSize() > MAX_PHOTO_BYTES) throw new BadRequestException("实拍图超过 10MB，请压缩后重试");
        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        if (!PHOTO_TYPES.contains(contentType)) throw new BadRequestException("只支持 JPEG、PNG、WebP 或 GIF 图片");
        AppearancePhotoEntity entity = new AppearancePhotoEntity();
        entity.setId("ap" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        entity.setMemberId(memberId);
        entity.setFileName(safeName(file.getOriginalFilename()));
        entity.setContentType(contentType);
        entity.setByteSize(file.getSize());
        try { entity.setFileContent(file.getBytes()); }
        catch (IOException exception) { throw new BadRequestException("读取实拍图失败，请重试"); }
        entity.setCreatedAt(LocalDateTime.now());
        photos.insert(entity);
        return photoDto(entity);
    }

    /** 读取当前成员的实拍原件；其他成员访问相同 id 时按不存在处理。 */
    public AppearancePhotoEntity photo(String memberId, String id) {
        requireMember(memberId);
        AppearancePhotoEntity entity = photos.selectOwnedWithContent(id, memberId);
        if (entity == null) throw new NotFoundException("实拍图不存在");
        return entity;
    }

    /** 删除当前成员明确移除的实拍原件。 */
    @Transactional
    public void deletePhoto(String memberId, String id) {
        requireMember(memberId);
        int deleted = photos.delete(new LambdaUpdateWrapper<AppearancePhotoEntity>()
                .eq(AppearancePhotoEntity::getId, id)
                .eq(AppearancePhotoEntity::getMemberId, memberId));
        if (deleted == 0) throw new NotFoundException("实拍图不存在");
    }

    private AppearanceWorkspaceDto normalize(AppearanceWorkspaceDto source, LocalDateTime updatedAt) {
        if (source == null) return empty(updatedAt);
        List<Garment> rawGarments = list(source.garments());
        List<Outfit> outfits = list(source.outfits()).stream().map(outfit -> new Outfit(
                text(outfit.id()), text(outfit.name()), distinct(outfit.garmentIds()), distinct(outfit.scenarios()),
                nullable(outfit.imageId()), text(outfit.note()))).toList();
        Map<String, Outfit> outfitsById = new HashMap<>();
        outfits.forEach(outfit -> outfitsById.put(outfit.id(), outfit));
        List<WearLog> wearLogs = list(source.wearLogs()).stream().map(log -> new WearLog(
                text(log.id()), log.date(), nullable(log.outfitId()), distinct(log.garmentIds()),
                nullable(log.imageId()), text(log.feeling())))
                .sorted((left, right) -> compareDates(right.date(), left.date())).toList();

        Map<String, Integer> counts = new HashMap<>();
        Map<String, LocalDate> lastWorn = new HashMap<>();
        for (WearLog log : wearLogs) {
            Set<String> worn = new LinkedHashSet<>(log.garmentIds());
            Outfit outfit = outfitsById.get(log.outfitId());
            if (outfit != null) worn.addAll(outfit.garmentIds());
            for (String garmentId : worn) {
                counts.merge(garmentId, 1, Integer::sum);
                if (log.date() != null && (lastWorn.get(garmentId) == null || log.date().isAfter(lastWorn.get(garmentId)))) {
                    lastWorn.put(garmentId, log.date());
                }
            }
        }
        List<Garment> garments = rawGarments.stream().map(item -> new Garment(
                text(item.id()), text(item.name()), text(item.category()), distinct(item.seasons()),
                distinct(item.styles()), distinct(item.colors()), distinct(item.occasions()), text(item.material()),
                text(item.status()), nullable(item.imageId()), item.purchaseDate(), lastWorn.get(item.id()),
                counts.getOrDefault(item.id(), 0), text(item.note()))).toList();
        Profile profile = source.profile() == null ? emptyProfile() : normalizeProfile(source.profile());
        List<Plan> plans = list(source.plans()).stream().map(plan -> new Plan(text(plan.id()), text(plan.type()),
                text(plan.title()), plan.dueDate(), text(plan.scenario()), nullable(plan.outfitId()),
                text(plan.status()), text(plan.note()))).toList();
        List<Note> notes = list(source.notes()).stream().map(note -> new Note(text(note.id()), text(note.title()),
                text(note.content()), distinct(note.tags()), note.createdAt())).toList();
        String principles = StringUtils.hasText(source.principles()) ? source.principles().trim() : DEFAULT_PRINCIPLES;
        return new AppearanceWorkspaceDto(garments, outfits, profile, plans, wearLogs, notes, principles, updatedAt);
    }

    private void validate(AppearanceWorkspaceDto workspace) {
        requireLimit(workspace.garments(), 500, "服饰物品");
        requireLimit(workspace.outfits(), 200, "穿搭组合");
        requireLimit(workspace.plans(), 500, "计划事项");
        requireLimit(workspace.wearLogs(), 2000, "穿着记录");
        requireLimit(workspace.notes(), 500, "穿搭笔记");
        Set<String> garmentIds = uniqueIds(workspace.garments().stream().map(Garment::id).toList(), "服饰物品");
        Set<String> outfitIds = uniqueIds(workspace.outfits().stream().map(Outfit::id).toList(), "穿搭组合");
        uniqueIds(workspace.plans().stream().map(Plan::id).toList(), "计划事项");
        uniqueIds(workspace.wearLogs().stream().map(WearLog::id).toList(), "穿着记录");
        uniqueIds(workspace.notes().stream().map(Note::id).toList(), "穿搭笔记");
        for (Garment garment : workspace.garments()) {
            requireText(garment.name(), 120, "物品名称");
            requireText(garment.category(), 32, "物品品类");
            if (!GARMENT_STATUSES.contains(garment.status())) throw new BadRequestException("物品状态不合法");
            maxText(garment.note(), 2000, "物品备注");
        }
        for (Outfit outfit : workspace.outfits()) {
            requireText(outfit.name(), 120, "穿搭名称");
            if (outfit.garmentIds().isEmpty()) throw new BadRequestException("穿搭组合至少选择一件物品");
            requireReferences(outfit.garmentIds(), garmentIds, "穿搭组合引用了不存在的物品");
            maxText(outfit.note(), 2000, "穿搭说明");
        }
        for (WearLog log : workspace.wearLogs()) {
            if (log.date() == null) throw new BadRequestException("穿着日期不能为空");
            if (log.date().isAfter(LocalDate.now())) throw new BadRequestException("实际穿着日期不能晚于今天");
            if (!StringUtils.hasText(log.outfitId()) && log.garmentIds().isEmpty()) {
                throw new BadRequestException("穿着记录至少选择一个组合或物品");
            }
            if (StringUtils.hasText(log.outfitId()) && !outfitIds.contains(log.outfitId())) {
                throw new BadRequestException("穿着记录引用了不存在的组合");
            }
            requireReferences(log.garmentIds(), garmentIds, "穿着记录引用了不存在的物品");
            maxText(log.imageId(), 32, "穿着实拍标识");
            maxText(log.feeling(), 2000, "穿着感受");
        }
        for (Plan plan : workspace.plans()) {
            if (!PLAN_TYPES.contains(plan.type())) throw new BadRequestException("计划类型不合法");
            if (!PLAN_STATUSES.contains(plan.status())) throw new BadRequestException("计划状态不合法");
            requireText(plan.title(), 160, "计划标题");
            if (plan.dueDate() == null) throw new BadRequestException("计划日期不能为空");
            if (StringUtils.hasText(plan.outfitId()) && !outfitIds.contains(plan.outfitId())) {
                throw new BadRequestException("场合计划引用了不存在的穿搭");
            }
            maxText(plan.note(), 2000, "计划备注");
        }
        for (Note note : workspace.notes()) {
            requireText(note.title(), 160, "笔记标题");
            requireText(note.content(), 5000, "笔记内容");
        }
        maxText(workspace.principles(), 2000, "形象理念");
        validateProfile(workspace.profile());
    }

    private static Profile normalizeProfile(Profile p) {
        return new Profile(p.heightCm(), p.weightKg(), p.shoulderCm(), p.waistCm(), p.measuredOn(),
                nullable(p.imageId()), text(p.skinTone()), text(p.faceShape()), text(p.hairTexture()),
                text(p.stylePreferences()), text(p.avoidances()), text(p.postureGoal()), p.hairScore(),
                p.clothingScore(), p.shoesScore(), p.accessoryScore(), p.bodyScore(), p.presenceScore());
    }

    private static void validateProfile(Profile p) {
        validateMeasure(p.heightCm(), 50, 260, "身高");
        validateMeasure(p.weightKg(), 10, 500, "体重");
        validateMeasure(p.shoulderCm(), 10, 100, "肩宽");
        validateMeasure(p.waistCm(), 20, 300, "腰围");
        maxText(p.imageId(), 32, "个人形象照标识");
        for (Integer score : List.of(nullToZero(p.hairScore()), nullToZero(p.clothingScore()),
                nullToZero(p.shoesScore()), nullToZero(p.accessoryScore()), nullToZero(p.bodyScore()),
                nullToZero(p.presenceScore()))) {
            if (score != 0 && score != 30 && score != 60 && score != 80 && score != 100) {
                throw new BadRequestException("自评只能选择 30、60、80 或 100 分");
            }
        }
        maxText(p.stylePreferences(), 2000, "风格偏好");
        maxText(p.avoidances(), 2000, "避雷记录");
        maxText(p.postureGoal(), 2000, "体态目标");
    }

    private AppearanceWorkspaceDto empty(LocalDateTime updatedAt) {
        return new AppearanceWorkspaceDto(List.of(), List.of(), emptyProfile(), List.of(), List.of(), List.of(),
                DEFAULT_PRINCIPLES, updatedAt);
    }

    private static Profile emptyProfile() {
        return new Profile(null, null, null, null, null, null, "", "", "", "", "", "",
                null, null, null, null, null, null);
    }

    private static AppearancePhotoDto photoDto(AppearancePhotoEntity entity) {
        return new AppearancePhotoDto(entity.getId(), entity.getFileName(), entity.getContentType(),
                entity.getByteSize(), "/api/appearance/photos/" + entity.getId());
    }

    private static <T> List<T> list(List<T> values) { return values == null ? List.of() : values; }
    private static String text(String value) { return value == null ? "" : value.trim(); }
    private static String nullable(String value) { return StringUtils.hasText(value) ? value.trim() : null; }
    private static List<String> distinct(List<String> values) {
        if (values == null) return List.of();
        return values.stream().filter(StringUtils::hasText).map(String::trim).distinct().limit(30).toList();
    }
    private static int compareDates(LocalDate left, LocalDate right) {
        if (left == null) return right == null ? 0 : -1;
        return right == null ? 1 : left.compareTo(right);
    }
    private static void requireMember(String memberId) {
        if (!StringUtils.hasText(memberId) || "unknown".equals(memberId)) throw new BadRequestException("无法识别当前成员");
    }
    private static <T> void requireLimit(List<T> values, int max, String label) {
        if (values.size() > max) throw new BadRequestException(label + "最多保存 " + max + " 条");
    }
    private static Set<String> uniqueIds(List<String> ids, String label) {
        Set<String> unique = new HashSet<>();
        for (String id : ids) {
            if (!StringUtils.hasText(id) || id.length() > 32) throw new BadRequestException(label + "标识不合法");
            if (!unique.add(id)) throw new BadRequestException(label + "存在重复标识");
        }
        return unique;
    }
    private static void requireReferences(List<String> ids, Set<String> known, String message) {
        if (!known.containsAll(ids)) throw new BadRequestException(message);
    }
    private static void requireText(String value, int max, String label) {
        if (!StringUtils.hasText(value)) throw new BadRequestException(label + "不能为空");
        maxText(value, max, label);
    }
    private static void maxText(String value, int max, String label) {
        if (value != null && value.length() > max) throw new BadRequestException(label + "最多 " + max + " 字");
    }
    private static void validateMeasure(Double value, double min, double max, String label) {
        if (value != null && (value < min || value > max)) throw new BadRequestException(label + "超出合理录入范围");
    }
    private static int nullToZero(Integer value) { return value == null ? 0 : value; }
    private static String safeName(String raw) {
        String value = StringUtils.hasText(raw) ? raw.replace('\\', '/').substring(raw.replace('\\', '/').lastIndexOf('/') + 1) : "形象实拍";
        value = value.replaceAll("[\\r\\n\\t]", "_").trim();
        if (value.isEmpty()) value = "形象实拍";
        return value.length() <= 255 ? value : value.substring(value.length() - 255);
    }
}
