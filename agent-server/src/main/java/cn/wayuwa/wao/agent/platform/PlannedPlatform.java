package cn.wayuwa.wao.agent.platform;

import cn.wayuwa.wao.agent.common.BadRequestException;

/**
 * 尚无官方开放接口、因此只能生成内容并导出的人工发布平台。
 *
 * <p>这些平台在能力声明里明确 publishSupported=false，任何发布调用都会抛错而不是返回假成功。
 * 等平台开放接口后，替换为真实适配器即可，主链路无需改动。
 */
public class PlannedPlatform implements PublishPlatform {
    private final PlatformType type;
    private final String note;

    public PlannedPlatform(PlatformType type, String note) {
        this.type = type;
        this.note = note;
    }

    @Override
    public PlatformType type() {
        return type;
    }

    @Override
    public PlatformCapability capability() {
        return PlatformCapability.planned(note);
    }

    @Override
    public String authorizeUrl(String state) {
        throw new BadRequestException(note);
    }

    @Override
    public PlatformIdentity exchangeCode(String code) {
        throw new BadRequestException(note);
    }

    @Override
    public PlatformIdentity refresh(PlatformCredential credential) {
        throw new BadRequestException(note);
    }

    @Override
    public PublishResult publish(PlatformCredential credential, PublishRequest request) {
        throw new BadRequestException(note);
    }
}
