package cn.wayuwa.wao.feishu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import java.net.URI;
import cn.wayuwa.wao.mapper.FeishuApiUsageMapper;
import org.junit.jupiter.api.Test;

class FeishuApiUsageRecorderTest {
    @Test
    void stripsIdentifiersAndQueryButPreservesDifferentApisAndMethods() {
        assertThat(FeishuApiUsageRecorder.endpoint("GET", URI.create("https://open.feishu.cn/open-apis/im/v1/messages/om_secret/reactions?emoji_type=x")))
                .isEqualTo("GET /open-apis/im/v1/messages/{id}/reactions");
        assertThat(FeishuApiUsageRecorder.endpoint("GET", URI.create("https://open.feishu.cn/open-apis/im/v1/messages?container_id=oc_secret")))
                .isEqualTo("GET /open-apis/im/v1/messages");
        assertThat(FeishuApiUsageRecorder.endpoint("DELETE", URI.create("https://open.feishu.cn/open-apis/im/v1/messages/om_secret/reactions/reaction_secret")))
                .isEqualTo("DELETE /open-apis/im/v1/messages/{id}/reactions/{id}");
    }

    @Test
    void recordsEachAttemptWithNoCredentials() {
        FeishuApiUsageMapper mapper = mock(FeishuApiUsageMapper.class);
        new FeishuApiUsageRecorder(mapper).record("GET", URI.create("https://open.feishu.cn/open-apis/im/v1/chats/oc_private/members?x=secret"));
        verify(mapper).record(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("GET /open-apis/im/v1/chats/{id}/members"), org.mockito.ArgumentMatchers.any());
    }
}
