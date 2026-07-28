package cn.wayuwa.wao.feishu;

import static org.assertj.core.api.Assertions.assertThat;

import cn.wayuwa.wao.auth.FeishuLoginProperties;
import org.junit.jupiter.api.Test;

class FeishuAppLinksTest {

    @Test
    void wrapsPathAsAppLinkSoCardsOpenInsideFeishu() {
        FeishuAppLinks links = new FeishuAppLinks(new FeishuLoginProperties(
                true, "cli_test", "secret", "https://wao.example/cb", "https://wao.example", "https://open.feishu.cn"));

        assertThat(links.taskUrl("p1", "t1")).isEqualTo(
                "https://applink.feishu.cn/client/web_app/open?appId=cli_test&path=%2Fprojects%2Fp1%3Ftask%3Dt1");
    }

    @Test
    void fallsBackToPlainSiteUrlWhenAppIsNotConfigured() {
        FeishuAppLinks links = new FeishuAppLinks(new FeishuLoginProperties(
                false, "", "", "", "", "https://open.feishu.cn"));

        assertThat(links.open("/kb/kb1?doc=d1")).isEqualTo("https://wao.wayuwa.cn/kb/kb1?doc=d1");
        assertThat(links.taskUrl("p1", "t1")).isEqualTo("https://wao.wayuwa.cn/projects/p1?task=t1");
    }
}
