package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.domain.TextPolishRecordEntity;
import cn.wayuwa.wao.dto.TextPolishRecordDto;
import cn.wayuwa.wao.dto.TextPolishWorkspaceDto;
import cn.wayuwa.wao.mapper.MemberPreferenceMapper;
import cn.wayuwa.wao.mapper.TextPolishRecordMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 管理成员级润色提示词，并记录每次真实 AI 润色的前后内容。 */
@Service
public class TextPolishService {

    static final String PROMPT_PREFERENCE_KEY = "text-polish.prompt.v1";
    static final String DEFAULT_PROMPT = """
            请按以下方法把原文整理成清晰、自然、可直接发送的表达：
            1. 看明白：先准确理解当前现状和真正要表达的意思。
            2. 想明白：梳理初步分析、核心判断和结论。
            3. 说明白：按前因、过程、结果重新组织，让读者容易理解。
            4. 干明白：涉及行动时，给出可执行的方法和具体步骤。

            不要机械添加四个标题；根据原文内容选择合适结构，保持说话人的语气。
            """;
    private static final int PROMPT_LIMIT = 4000;
    private static final int ORIGINAL_LIMIT = 12000;

    private final MemberPreferenceMapper preferences;
    private final TextPolishRecordMapper records;
    private final AiAssistantService assistant;

    public TextPolishService(MemberPreferenceMapper preferences, TextPolishRecordMapper records,
            AiAssistantService assistant) {
        this.preferences = preferences;
        this.records = records;
        this.assistant = assistant;
    }

    /** 读取当前成员已保存的提示词和最近 50 条真实润色历史。 */
    public TextPolishWorkspaceDto workspace(String memberId) {
        return new TextPolishWorkspaceDto(prompt(memberId), history(memberId));
    }

    /** 保存当前成员的润色方法；空白和超长内容不会覆盖原配置。 */
    public String savePrompt(String memberId, String value) {
        String normalized = normalizePrompt(value);
        preferences.upsert(memberId, PROMPT_PREFERENCE_KEY, normalized);
        return normalized;
    }

    /** 使用当前服务端提示词完成一次真实润色，成功后原子地写入可追溯历史。 */
    public TextPolishRecordDto run(String memberId, String originalText) {
        String original = originalText == null ? "" : originalText.trim();
        if (!StringUtils.hasText(original)) throw new BadRequestException("请先输入需要润色的原文");
        if (original.length() > ORIGINAL_LIMIT) {
            throw new BadRequestException("一次最多润色 12000 字，请拆分后重试");
        }
        String prompt = prompt(memberId);
        String polished = assistant.polishTextWithPrompt(original, memberId, prompt);
        if (!StringUtils.hasText(polished)) throw new BadRequestException("AI 没有返回有效润色结果");

        TextPolishRecordEntity entity = new TextPolishRecordEntity();
        entity.setId("tp" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        entity.setMemberId(memberId);
        entity.setPromptSnapshot(prompt);
        entity.setOriginalText(original);
        entity.setPolishedText(polished.trim());
        entity.setCreatedAt(LocalDateTime.now());
        records.insert(entity);
        return dto(entity);
    }

    /** 返回当前成员最近 50 条记录，查询条件始终包含成员 id。 */
    public List<TextPolishRecordDto> history(String memberId) {
        return records.listOwned(memberId)
                .stream().map(TextPolishService::dto).toList();
    }

    private String prompt(String memberId) {
        String stored = preferences.find(memberId, PROMPT_PREFERENCE_KEY);
        return StringUtils.hasText(stored) ? stored : DEFAULT_PROMPT;
    }

    private static String normalizePrompt(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!StringUtils.hasText(normalized)) throw new BadRequestException("润色提示词不能为空");
        if (normalized.length() > PROMPT_LIMIT) throw new BadRequestException("润色提示词最多 4000 字");
        return normalized;
    }

    private static TextPolishRecordDto dto(TextPolishRecordEntity entity) {
        return new TextPolishRecordDto(entity.getId(), entity.getPromptSnapshot(), entity.getOriginalText(),
                entity.getPolishedText(), entity.getCreatedAt());
    }
}
