package cn.wayuwa.wao.agent.platform;

import java.util.List;

/**
 * 一个平台当前真实具备的能力。前端按此渲染，不允许对没有官方接口的平台展示“发布成功”。
 *
 * @param oauthSupported   是否可以通过官方 OAuth 接管账号
 * @param publishSupported 是否可以通过官方接口真实发布
 * @param mediaKinds       支持的素材形态，取值见 {@link MediaKind}
 * @param note             面向用户的说明，解释当前能做到什么、缺什么
 */
public record PlatformCapability(boolean oauthSupported, boolean publishSupported,
                                 List<String> mediaKinds, String note) {

    public static PlatformCapability planned(String note) {
        return new PlatformCapability(false, false, List.of(), note);
    }
}
