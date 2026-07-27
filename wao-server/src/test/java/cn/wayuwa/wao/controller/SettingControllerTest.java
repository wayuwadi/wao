package cn.wayuwa.wao.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.dto.SaveSiteInfoRequest;
import cn.wayuwa.wao.dto.SiteInfoDto;
import cn.wayuwa.wao.feishu.FeishuWebhookNotifier;
import cn.wayuwa.wao.service.SettingService;
import org.junit.jupiter.api.Test;

class SettingControllerTest {

    private final SettingService settings = mock(SettingService.class);
    private final SettingController controller = new SettingController(settings, mock(FeishuWebhookNotifier.class));

    @Test
    void siteInfoUsesPublicDefaultsBeforeFirstSave() {
        when(settings.get(SettingService.SITE_NAME, "WAO Life OS")).thenReturn("WAO Life OS");
        when(settings.get(SettingService.SITE_DESCRIPTION, "个人事务与 AI 协作系统")).thenReturn("个人事务与 AI 协作系统");
        when(settings.get(SettingService.SITE_ICP_NUMBER, "豫ICP备2024105476号-1")).thenReturn("豫ICP备2024105476号-1");
        when(settings.get(SettingService.SITE_ICP_URL, "https://beian.miit.gov.cn")).thenReturn("https://beian.miit.gov.cn");

        SiteInfoDto result = controller.siteInfo().data();

        assertThat(result.icpNumber()).isEqualTo("豫ICP备2024105476号-1");
        assertThat(result.icpUrl()).isEqualTo("https://beian.miit.gov.cn");
    }

    @Test
    void saveSiteInfoTrimsAndPersistsAllFields() {
        when(settings.get(SettingService.SITE_NAME, "WAO Life OS")).thenReturn("WAO");
        when(settings.get(SettingService.SITE_DESCRIPTION, "个人事务与 AI 协作系统")).thenReturn("说明");
        when(settings.get(SettingService.SITE_ICP_NUMBER, "豫ICP备2024105476号-1")).thenReturn("豫ICP备2024105476号-1");
        when(settings.get(SettingService.SITE_ICP_URL, "https://beian.miit.gov.cn")).thenReturn("https://beian.miit.gov.cn");

        controller.saveSiteInfo(new SaveSiteInfoRequest(" WAO ", " 说明 ", " 豫ICP备2024105476号-1 ", " https://beian.miit.gov.cn "));

        verify(settings).put(SettingService.SITE_NAME, "WAO");
        verify(settings).put(SettingService.SITE_DESCRIPTION, "说明");
        verify(settings).put(SettingService.SITE_ICP_NUMBER, "豫ICP备2024105476号-1");
        verify(settings).put(SettingService.SITE_ICP_URL, "https://beian.miit.gov.cn");
    }

    @Test
    void rejectsUnsafeFilingUrl() {
        SaveSiteInfoRequest request = new SaveSiteInfoRequest("WAO", "", "备案号", "javascript:alert(1)");

        assertThatThrownBy(() -> controller.saveSiteInfo(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTP");
    }
}
