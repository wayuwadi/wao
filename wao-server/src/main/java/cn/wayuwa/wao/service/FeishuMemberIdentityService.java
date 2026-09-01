package cn.wayuwa.wao.service;

import cn.wayuwa.wao.domain.AccountEntity;
import cn.wayuwa.wao.domain.AccountIdentityEntity;
import cn.wayuwa.wao.feishu.FeishuOpenApiClient;
import cn.wayuwa.wao.mapper.AccountIdentityMapper;
import cn.wayuwa.wao.mapper.AccountMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 把群消息里的飞书身份安全解析为已经绑定的 WAO 成员。 */
@Service
public class FeishuMemberIdentityService {
    private static final Logger log = LoggerFactory.getLogger(FeishuMemberIdentityService.class);

    private final AccountIdentityMapper identities;
    private final AccountMapper accounts;
    private final SettingService settings;
    private final FeishuOpenApiClient feishu;

    public FeishuMemberIdentityService(AccountIdentityMapper identities, AccountMapper accounts,
            SettingService settings, FeishuOpenApiClient feishu) {
        this.identities = identities;
        this.accounts = accounts;
        this.settings = settings;
        this.feishu = feishu;
    }

    /** 先按当前应用 open_id 查绑定，未命中时再通过群成员列表换算 union_id。 */
    public String resolvePolledMember(String chatId, String openId) {
        String direct = memberId(identities.findByProviderSubject("feishu", openId));
        if (StringUtils.hasText(direct)) return direct;
        String appId = settings.get(SettingService.CHAT_SYNC_APP_ID, "");
        String appSecret = settings.get(SettingService.CHAT_SYNC_APP_SECRET, "");
        if (!StringUtils.hasText(appId) || !StringUtils.hasText(appSecret)
                || !StringUtils.hasText(chatId) || !StringUtils.hasText(openId)) {
            return null;
        }
        try {
            String token = feishu.tenantAccessToken(appId, appSecret);
            String unionId = feishu.chatMemberUnionId(token, chatId, openId);
            return StringUtils.hasText(unionId)
                    ? memberId(identities.findByProviderUnionSubject("feishu", unionId)) : null;
        } catch (RuntimeException exception) {
            log.warn("飞书群成员身份换算失败（凭据与成员标识已隐藏）");
            return null;
        }
    }

    private String memberId(AccountIdentityEntity identity) {
        if (identity == null) return null;
        AccountEntity account = accounts.selectById(identity.getAccountId());
        return account != null && StringUtils.hasText(account.getMemberId())
                ? account.getMemberId() : null;
    }
}
