package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.domain.TianrenDailyReadingEntity;
import cn.wayuwa.wao.dto.AssistantChatRequest;
import cn.wayuwa.wao.dto.SharedTianrenReadingDto;
import cn.wayuwa.wao.dto.TianrenReadingDto;
import cn.wayuwa.wao.mapper.TianrenDailyReadingMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** “天人一卦”每日存档与 AI 解读上下文服务。 */
@Service
public class TianrenReadingService {

    private static final ZoneId WAO_ZONE = ZoneId.of("Asia/Shanghai");
    private static final TypeReference<List<Integer>> LINES_TYPE = new TypeReference<>() { };
    private static final SecureRandom SHARE_RANDOM = new SecureRandom();

    private final TianrenDailyReadingMapper mapper;
    private final TianrenHexagramEngine engine;
    private final AiAssistantConversationService conversations;
    private final ObjectMapper json;

    public TianrenReadingService(TianrenDailyReadingMapper mapper, TianrenHexagramEngine engine,
            AiAssistantConversationService conversations, ObjectMapper json) {
        this.mapper = mapper;
        this.engine = engine;
        this.conversations = conversations;
        this.json = json;
    }

    /**
     * 读取当前成员今天已经起出的卦；尚未主动起卦时返回 null。
     *
     * @param memberId 当前登录成员
     * @return 今日卦象或 null
     */
    public TianrenReadingDto today(String memberId) {
        TianrenDailyReadingEntity row = find(memberId, LocalDate.now(WAO_ZONE));
        return row == null ? null : view(row);
    }

    /**
     * 为当前成员生成今日唯一卦象；并发或重复请求都返回同一份服务端存档。
     *
     * @param memberId 当前登录成员
     * @return 今日卦象
     */
    @Transactional
    public TianrenReadingDto createToday(String memberId) {
        requireMember(memberId);
        LocalDate date = LocalDate.now(WAO_ZONE);
        TianrenDailyReadingEntity existing = find(memberId, date);
        if (existing != null) return view(existing);

        TianrenHexagramEngine.Reading cast = engine.describe(engine.castLines());
        LocalDateTime now = LocalDateTime.now(WAO_ZONE);
        TianrenDailyReadingEntity row = new TianrenDailyReadingEntity();
        row.setId("tr" + UUID.randomUUID().toString().replace("-", "").substring(0, 22));
        row.setMemberId(memberId);
        row.setReadingDate(date);
        row.setLinesJson(write(cast.lines()));
        row.setPrimaryNumber(cast.primary().number());
        row.setRelatingNumber(cast.relating() == null ? null : cast.relating().number());
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        try {
            mapper.insert(row);
            return view(row);
        } catch (DuplicateKeyException exception) {
            TianrenDailyReadingEntity raced = find(memberId, date);
            if (raced != null) return view(raced);
            throw exception;
        }
    }

    /**
     * 返回当前成员最近的每日卦象，按日期倒序，供跨设备回看。
     *
     * @param memberId 当前登录成员
     * @param limit 1 到 60 条
     * @return 最近卦象
     */
    public List<TianrenReadingDto> history(String memberId, int limit) {
        requireMember(memberId);
        int bounded = Math.max(1, Math.min(limit, 60));
        return mapper.selectList(new LambdaQueryWrapper<TianrenDailyReadingEntity>()
                        .eq(TianrenDailyReadingEntity::getMemberId, memberId)
                        .orderByDesc(TianrenDailyReadingEntity::getReadingDate)
                        .last("LIMIT " + bounded))
                .stream().map(this::view).toList();
    }

    /**
     * 为当前成员拥有的一份卦象按需生成稳定的公开分享凭据。
     *
     * <p>条件更新保证并发点击只会落下一个 token；没有猜中凭据的人无法从公开接口枚举成员历史。
     *
     * @param memberId 当前登录成员
     * @param readingId 要分享的卦象存档 id
     * @return 可放进公开结果页路径的随机凭据
     */
    public String createShareToken(String memberId, String readingId) {
        requireMember(memberId);
        if (readingId == null || readingId.isBlank()) throw new BadRequestException("卦象标识不能为空");
        TianrenDailyReadingEntity row = findOwned(memberId, readingId);
        if (row == null) throw new NotFoundException("卦象不存在");
        if (row.getShareToken() != null && !row.getShareToken().isBlank()) return row.getShareToken();

        for (int attempt = 0; attempt < 3; attempt++) {
            String token = randomShareToken();
            try {
                int updated = mapper.update(null, new UpdateWrapper<TianrenDailyReadingEntity>()
                        .eq("id", readingId)
                        .eq("member_id", memberId)
                        .isNull("share_token")
                        .set("share_token", token)
                        .set("updated_at", LocalDateTime.now(WAO_ZONE)));
                if (updated == 1) return token;
            } catch (DuplicateKeyException ignored) {
                // 极低概率的随机串冲突，直接换一个；不得覆盖别的卦象已经发出的链接。
            }
            TianrenDailyReadingEntity latest = findOwned(memberId, readingId);
            if (latest != null && latest.getShareToken() != null && !latest.getShareToken().isBlank()) {
                return latest.getShareToken();
            }
        }
        throw new IllegalStateException("分享链接生成失败，请稍后重试");
    }

    private static String randomShareToken() {
        byte[] bytes = new byte[16];
        SHARE_RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /**
     * 按不可猜测凭据读取公开卦象与最新 AI 自照投影。
     *
     * <p>只读取卦象绑定且归属同一成员的最新 assistant 回复；不返回成员身份、存档 id、
     * 用户关注点原文、会话 id 或完整会话。
     *
     * @param token 分享凭据
     * @return 可公开展示的卦象
     */
    public SharedTianrenReadingDto shared(String token) {
        if (token == null || !token.matches("[0-9a-f]{32}")) throw new NotFoundException("分享链接无效");
        TianrenDailyReadingEntity row = mapper.selectOne(new LambdaQueryWrapper<TianrenDailyReadingEntity>()
                .eq(TianrenDailyReadingEntity::getShareToken, token)
                .last("LIMIT 1"));
        if (row == null) throw new NotFoundException("分享链接无效");
        TianrenReadingDto reading = view(row);
        String interpretation = conversations.latestAssistantReply(row.getConversationId(), row.getMemberId());
        return new SharedTianrenReadingDto(
                reading.date(), reading.lines(), reading.changingLines(), reading.primary(), reading.relating(),
                interpretation, reading.createdAt());
    }

    /**
     * 为今日卦象准备专用 AI 会话和可信卦象上下文；首次解读会创建成员私有会话。
     *
     * @param memberId 当前登录成员
     * @param focus 可选的本次关注点
     * @return 可直接交给只读 Codex 队列的请求与服务端上下文
     */
    @Transactional
    public PreparedInterpretation prepareInterpretation(String memberId, String focus) {
        TianrenDailyReadingEntity row = find(memberId, LocalDate.now(WAO_ZONE));
        if (row == null) throw new BadRequestException("请先起出今天的一卦");
        if (row.getConversationId() == null || row.getConversationId().isBlank()) {
            row.setConversationId(conversations.create(memberId).id());
            row.setUpdatedAt(LocalDateTime.now(WAO_ZONE));
            mapper.updateById(row);
        }

        TianrenReadingDto reading = view(row);
        String trimmed = focus == null ? "" : focus.trim();
        String message = trimmed.isEmpty()
                ? "请解读我今天的天人一卦，给出适合今天实践的反思与生活节律建议。"
                : "请围绕这个关注解读我今天的天人一卦：" + trimmed;
        AssistantChatRequest request = new AssistantChatRequest(
                message, List.of(), null, row.getConversationId(), null);
        return new PreparedInterpretation(request, trustedContext(reading));
    }

    private TianrenDailyReadingEntity find(String memberId, LocalDate date) {
        requireMember(memberId);
        return mapper.selectOne(new LambdaQueryWrapper<TianrenDailyReadingEntity>()
                .eq(TianrenDailyReadingEntity::getMemberId, memberId)
                .eq(TianrenDailyReadingEntity::getReadingDate, date)
                .last("LIMIT 1"));
    }

    /** 读取指定成员拥有的真实卦象，供受控群通知生成快照。 */
    public TianrenReadingDto getOwned(String memberId, String readingId) {
        requireMember(memberId);
        TianrenDailyReadingEntity row = findOwned(memberId, readingId);
        if (row == null) throw new NotFoundException("卦象不存在");
        return view(row);
    }

    private TianrenDailyReadingEntity findOwned(String memberId, String readingId) {
        return mapper.selectOne(new LambdaQueryWrapper<TianrenDailyReadingEntity>()
                .eq(TianrenDailyReadingEntity::getId, readingId)
                .eq(TianrenDailyReadingEntity::getMemberId, memberId)
                .last("LIMIT 1"));
    }

    private TianrenReadingDto view(TianrenDailyReadingEntity row) {
        TianrenHexagramEngine.Reading reading = engine.describe(read(row.getLinesJson()));
        if (!row.getPrimaryNumber().equals(reading.primary().number())
                || !java.util.Objects.equals(row.getRelatingNumber(),
                        reading.relating() == null ? null : reading.relating().number())) {
            throw new IllegalStateException("每日卦象存档校验失败");
        }
        return new TianrenReadingDto(
                row.getId(), row.getReadingDate(), reading.lines(), reading.changingLines(),
                reading.primary(), reading.relating(), row.getConversationId(), row.getCreatedAt());
    }

    private String trustedContext(TianrenReadingDto reading) {
        String relating = reading.relating() == null
                ? "无变爻，本次不生成之卦"
                : "第 " + reading.relating().number() + " 卦「" + reading.relating().name() + "」"
                        + "（上" + reading.relating().upper().name() + "下" + reading.relating().lower().name() + "）";
        return """
                <authoritative_tianren_cast>
                日期（Asia/Shanghai）：%s
                三枚硬币爻值（自初爻到上爻）：%s
                变爻位置：%s
                本卦：第 %d 卦「%s」（上%s下%s）
                之卦：%s
                </authoritative_tianren_cast>
                """.formatted(
                reading.date(), reading.lines(), reading.changingLines().isEmpty() ? "无" : reading.changingLines(),
                reading.primary().number(), reading.primary().name(),
                reading.primary().upper().name(), reading.primary().lower().name(), relating);
    }

    private List<Integer> read(String raw) {
        try {
            return json.readValue(raw, LINES_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("每日卦象存档无法读取", exception);
        }
    }

    private String write(List<Integer> lines) {
        try {
            return json.writeValueAsString(lines);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("每日卦象存档无法保存", exception);
        }
    }

    private static void requireMember(String memberId) {
        if (memberId == null || memberId.isBlank()) throw new BadRequestException("无法识别当前成员");
    }

    /** 专用解读所需的普通会话请求和不可由浏览器篡改的卦象上下文。 */
    public record PreparedInterpretation(AssistantChatRequest request, String trustedContext) {
    }
}
