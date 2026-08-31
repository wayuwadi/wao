package cn.wayuwa.wao.auth;

import java.time.Instant;
import java.util.Optional;

/** 跨节点保存微信公众号短时扫码登录状态。 */
public interface WechatOfficialLoginStore {

    /** 保存五分钟有效的浏览器登录会话和二维码场景映射。 */
    void save(LoginSession session);

    /** 按不可猜测会话 id 读取当前扫码状态。 */
    Optional<LoginSession> find(String sessionId);

    /** 将微信扫码事件关联到对应浏览器会话。 */
    boolean markScanned(String scene, String openId);

    /** 校验扫码用户回复的验证码并完成对应会话。 */
    CodeResult complete(String openId, String verificationCode);

    /** 读取共享的公众号 access token。 */
    Optional<String> accessToken();

    /** 缓存公众号 access token 到其安全有效期。 */
    void saveAccessToken(String accessToken, long expiresInSeconds);

    /** 清除已被微信判定无效的 access token。 */
    void clearAccessToken();

    enum Status { WAITING_SCAN, WAITING_CODE, COMPLETED }

    enum CodeResult { MATCHED, INVALID, NO_ACTIVE_SCAN }

    record LoginSession(String id, String verificationCode, Status status, String openId, Instant expiresAt,
                        String qrCodeUrl) { }
}
