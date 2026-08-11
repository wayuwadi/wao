package cn.wayuwa.wao.agent.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.wayuwa.wao.agent.common.BadRequestException;
import org.junit.jupiter.api.Test;

/** 没有官方发布接口的平台必须诚实地拒绝，而不是返回假成功。 */
class PlannedPlatformTest {

    @Test
    void declaresItselfUnsupportedInsteadOfPretending() {
        PlannedPlatform platform = new PlannedPlatform(PlatformType.XIAOHONGSHU, "小红书暂无内容发布接口");

        PlatformCapability capability = platform.capability();

        assertThat(capability.publishSupported()).isFalse();
        assertThat(capability.oauthSupported()).isFalse();
        assertThat(capability.mediaKinds()).isEmpty();
        assertThat(capability.note()).contains("小红书");
    }

    @Test
    void publishThrowsWithActionableReason() {
        PlannedPlatform platform = new PlannedPlatform(PlatformType.WECHAT_CHANNELS, "视频号没有第三方发布接口");

        assertThatThrownBy(() -> platform.publish(null, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("视频号没有第三方发布接口");
        assertThatThrownBy(() -> platform.authorizeUrl("state"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void registryExposesEveryPlatformInEnumOrder() {
        PlatformRegistry registry = new PlatformRegistry(java.util.List.of(
                new PlannedPlatform(PlatformType.XIAOHONGSHU, "x"),
                new PlannedPlatform(PlatformType.DOUYIN, "d")));

        assertThat(registry.all().stream().map(PublishPlatform::type))
                .containsExactly(PlatformType.DOUYIN, PlatformType.XIAOHONGSHU);
        assertThatThrownBy(() -> registry.get(PlatformType.WECHAT_MP))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void parseRejectsUnknownPlatformCode() {
        assertThat(PlatformType.parse("douyin")).isEqualTo(PlatformType.DOUYIN);
        assertThatThrownBy(() -> PlatformType.parse("bilibili"))
                .isInstanceOf(BadRequestException.class);
    }
}
