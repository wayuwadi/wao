package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.domain.TianrenDailyReadingEntity;
import cn.wayuwa.wao.dto.AssistantChatRequest;
import cn.wayuwa.wao.dto.TianrenReadingDto;
import cn.wayuwa.wao.mapper.TianrenDailyReadingMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
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
