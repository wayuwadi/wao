package cn.wayuwa.wao.agent.dto;

import java.util.List;

/**
 * 一个平台的能力与已绑定账号，供前端渲染「连接设置」。
 *
 * @param oauthSupported   是否可授权接管
 * @param publishSupported 是否可通过官方接口真实发布
 * @param mediaKinds       支持的素材形态
 * @param note             能力说明，解释缺什么
 */
public record PlatformSummaryDto(String platform, String label, boolean oauthSupported, boolean publishSupported,
                                 List<String> mediaKinds, String note, List<PlatformAccountDto> accounts) {
}
