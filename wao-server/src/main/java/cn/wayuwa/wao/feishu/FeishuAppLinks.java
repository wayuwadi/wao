package cn.wayuwa.wao.feishu;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import cn.wayuwa.wao.auth.FeishuLoginProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 飞书卡片跳转链接：把站内路径包装成 AppLink，让「去处理」按钮在飞书容器内直接打开 WAO。
 *
 * <p>普通 https 链接在飞书里点开会跳到系统浏览器，还要重新登录一次，处理链路就断了。
 * AppLink（{@code applink.feishu.cn/client/web_app/open}）会让飞书客户端用「网页应用」
 * 容器打开页面 —— 移动端是内嵌 WebView、桌面端是飞书内的应用窗口，配合容器内免登
 * （见 {@code FeishuLoginController} 的 in-app 接口）实现不出飞书的处理闭环。
 *
 * <p>前提是开放平台里给登录用的自建应用开启了「网页应用」能力并把主页配成 {@link #SITE}。
 * 没配 App ID 时回退为普通网页地址，卡片按钮照常可用。
 */
@Component
public class FeishuAppLinks {

    /** 线上站点地址；卡片按钮要跳的是公网地址，不是容器内部地址。 */
    public static final String SITE = "https://wao.wayuwa.cn";

    private final FeishuLoginProperties login;

    public FeishuAppLinks(FeishuLoginProperties login) {
        this.login = login;
    }

    /**
     * 把站内路径变成卡片按钮链接。
     *
     * @param path 以 {@code /} 开头的站内路径，可带查询串
     * @return 配置了飞书应用时是 AppLink（飞书内打开），否则是普通网页地址
     */
    public String open(String path) {
        if (!login.enabled() || !StringUtils.hasText(login.appId())) {
            return SITE + path;
        }
        return "https://applink.feishu.cn/client/web_app/open?appId=" + login.appId()
                + "&path=" + URLEncoder.encode(path, StandardCharsets.UTF_8);
    }

    /** 任务详情页链接（卡片按钮统一落到这条任务）。 */
    public String taskUrl(String projectId, String taskId) {
        return open("/projects/" + projectId + "?task=" + taskId);
    }
}
