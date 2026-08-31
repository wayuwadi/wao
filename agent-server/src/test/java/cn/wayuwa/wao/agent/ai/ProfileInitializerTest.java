package cn.wayuwa.wao.agent.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ProfileInitializerTest {

    @Test
    void realModelFieldsBecomeEditableProfileWithoutInventedPlatformState() throws Exception {
        ChatClient chat = mock(ChatClient.class);
        String json = """
                {"niche":"真实 AI 工作流","audience":"忙碌上班族","tone":"克制实用",
                 "avoid":"未经验证的数据","contentTopics":"工具实测、流程复盘",
                 "lengthStructure":"先结论后步骤","updateFrequency":"每周两篇",
                 "primaryPlatforms":"公众号","imageStyle":"暖色工作场景"}
                """;
        when(chat.complete(anyString(), anyString(), anyDouble())).thenReturn(json);
        when(chat.readJson(json, false)).thenReturn(new ObjectMapper().readTree(json));

        var profile = new ProfileInitializer(chat).initialize("分享自己用过的 AI 工作流");

        assertThat(profile.niche()).isEqualTo("真实 AI 工作流");
        assertThat(profile.updateFrequency()).isEqualTo("每周两篇");
        assertThat(profile.imageStyle()).isEqualTo("暖色工作场景");
        assertThat(profile.configured()).isFalse();
    }
}
