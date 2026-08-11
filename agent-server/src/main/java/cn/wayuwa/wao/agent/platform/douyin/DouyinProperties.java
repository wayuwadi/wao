package cn.wayuwa.wao.agent.platform.douyin;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/**
 * 抖音开放平台应用配置。全部来自节点环境变量，仓库内只保留占位。
 *
 * @param clientKey    开放平台应用 client_key
 * @param clientSecret 开放平台应用 client_secret
 * @param redirectUri  授权回调地址，必须与开放平台后台登记的完全一致
 * @param scope        申请的权限范围，发布视频需要 video.create.bind
 * @param apiBase      接口根地址，测试时指向本地桩服务
 */
@ConfigurationProperties(prefix = "wao-agent.platform.douyin")
public record DouyinProperties(String clientKey, String clientSecret, String redirectUri,
                               String scope, String apiBase) {

    /** 凭据齐全才认为抖音适配器可用。 */
    public boolean configured() {
        return StringUtils.hasText(clientKey) && StringUtils.hasText(clientSecret)
                && StringUtils.hasText(redirectUri);
    }

    public String base() {
        String value = apiBase == null || apiBase.isBlank() ? "https://open.douyin.com" : apiBase.trim();
        return value.replaceAll("/+$", "");
    }
}
