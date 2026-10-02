package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.domain.AccountEntity;
import cn.wayuwa.wao.domain.AccountIdentityEntity;
import cn.wayuwa.wao.domain.FeishuQuickCollectAiRunTriggerEntity;
import cn.wayuwa.wao.domain.FeishuQuickCollectEventEntity;
import cn.wayuwa.wao.domain.TaskEntity;
import cn.wayuwa.wao.dto.AiRunDto;
import cn.wayuwa.wao.feishu.FeishuOpenApiClient;
import cn.wayuwa.wao.mapper.AccountIdentityMapper;
import cn.wayuwa.wao.mapper.AccountMapper;
import cn.wayuwa.wao.mapper.FeishuQuickCollectAiRunTriggerMapper;
import cn.wayuwa.wao.mapper.FeishuQuickCollectEventMapper;
import cn.wayuwa.wao.mapper.TaskMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

class FeishuQuickCollectAiRunServiceTest {
    private final FeishuQuickCollectEventMapper collectEvents = mock(FeishuQuickCollectEventMapper.class);
    private final FeishuQuickCollectAiRunTriggerMapper triggers = mock(FeishuQuickCollectAiRunTriggerMapper.class);
    private final AccountIdentityMapper identities = mock(AccountIdentityMapper.class);
    private final AccountMapper accounts = mock(AccountMapper.class);
    private final TaskMapper tasks = mock(TaskMapper.class);
    private final AiRunService aiRuns = mock(AiRunService.class);
    private final SettingService settings = mock(SettingService.class);
    private final FeishuOpenApiClient feishu = mock(FeishuOpenApiClient.class);
    private final FeishuQuickCollectReactionPolicy reactionPolicy = new FeishuQuickCollectReactionPolicy(settings);
    private final FeishuQuickCollectAiRunService service = new FeishuQuickCollectAiRunService(
            collectEvents, triggers, identities, accounts, tasks, aiRuns, settings, feishu, reactionPolicy);
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void retiredReactionsNeverReadOrDispatchTasks() {
        assertThat(service.trigger(json.createObjectNode(), "https://example.test")).isFalse();
        assertThat(service.triggerPolled("old-message", null, "https://example.test")).isFalse();
        org.mockito.Mockito.verifyNoInteractions(collectEvents, triggers, identities, accounts, tasks, aiRuns, settings, feishu);
    }
}
