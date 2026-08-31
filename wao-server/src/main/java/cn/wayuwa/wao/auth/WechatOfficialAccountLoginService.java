package cn.wayuwa.wao.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.StringReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

/** 微信公众号带参二维码、消息回调与一次性验证码登录流程。 */
@Service
public class WechatOfficialAccountLoginService {
    private static final Logger log = LoggerFactory.getLogger(WechatOfficialAccountLoginService.class);
    private static final Duration SESSION_TTL = Duration.ofMinutes(5);
    private static final int MAX_XML_LENGTH = 64 * 1024;
    private final WechatOfficialAccountConfigService config;
    private final WechatOfficialLoginStore store;
    private final RestClient client;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public WechatOfficialAccountLoginService(WechatOfficialAccountConfigService config,
                                             WechatOfficialLoginStore store, RestClient.Builder builder) {
        this.config = config;
        this.store = store;
        this.client = builder.baseUrl(config.apiBaseUrl()).build();
    }

    /** 兼容认证状态机的独立单元测试。 */
    WechatOfficialAccountLoginService(WechatOfficialAccountProperties properties,
            WechatOfficialLoginStore store, RestClient.Builder builder) {
        this(new WechatOfficialAccountConfigService(properties), store, builder);
    }

    /** 创建带参公众号二维码和仅对本次扫码有效的四位验证码。 */
    public WechatOfficialLoginStore.LoginSession createSession() {
        WechatOfficialAccountConfigService.Effective effective = requireEnabled();
        String sessionId = randomHex(16);
        String verificationCode = String.format("%04d", random.nextInt(10_000));
        QrResponse qr = createQr(accessToken(effective), sessionId);
        if (qr != null && tokenInvalid(qr.errcode())) {
            store.clearAccessToken();
            qr = createQr(accessToken(effective), sessionId);
        }
        if (qr == null || !StringUtils.hasText(qr.ticket())) {
            log.warn("Wechat official QR creation failed: code={}", qr == null ? null : qr.errcode());
            throw new WechatLoginException("公众号登录二维码生成失败，请检查接口权限与 IP 白名单");
        }
        Instant expiresAt = Instant.now().plus(SESSION_TTL);
        String qrCodeUrl = effective.qrBaseUrl() + "/cgi-bin/showqrcode?ticket=" + encode(qr.ticket());
        WechatOfficialLoginStore.LoginSession session = new WechatOfficialLoginStore.LoginSession(
                sessionId, verificationCode, WechatOfficialLoginStore.Status.WAITING_SCAN, null,
                expiresAt, qrCodeUrl);
        store.save(session);
        return session;
    }

    /** 读取浏览器登录会话；Redis TTL 到期后返回空。 */
    public Optional<WechatOfficialLoginStore.LoginSession> session(String sessionId) {
        if (!StringUtils.hasText(sessionId) || sessionId.length() > 64) return Optional.empty();
        return store.find(sessionId);
    }

    /** 校验微信公众号服务器配置或消息回调签名。 */
    public boolean validSignature(String signature, String timestamp, String nonce) {
        WechatOfficialAccountConfigService.Effective effective = config.current();
        if (!effective.configured() || !StringUtils.hasText(signature)
                || !StringUtils.hasText(timestamp) || !StringUtils.hasText(nonce)) return false;
        try {
            String source = Stream.of(effective.token(), timestamp, nonce).sorted().collect(Collectors.joining());
            String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1")
                    .digest(source.getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                    signature.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot verify Wechat callback signature", exception);
        }
    }

    /** 处理扫码、关注与文本消息，并返回微信公众号要求的被动回复 XML。 */
    public String handleMessage(String xml) {
        OfficialMessage message = parseMessage(xml);
        String reply;
        if ("event".equalsIgnoreCase(message.messageType())
                && ("subscribe".equalsIgnoreCase(message.event()) || "SCAN".equalsIgnoreCase(message.event()))) {
            String scene = Optional.ofNullable(message.eventKey()).orElse("").replaceFirst("^qrscene_", "");
            boolean found = StringUtils.hasText(scene) && store.markScanned(scene, message.fromUser());
            reply = found ? "已识别扫码，请回复页面上的 4 位验证码完成 WAO 登录。"
                    : "登录二维码已过期，请返回 WAO 登录页重新获取。";
        } else if ("text".equalsIgnoreCase(message.messageType())) {
            String code = Optional.ofNullable(message.content()).orElse("").trim();
            WechatOfficialLoginStore.CodeResult result = code.matches("\\d{4}")
                    ? store.complete(message.fromUser(), code) : WechatOfficialLoginStore.CodeResult.INVALID;
            reply = switch (result) {
                case MATCHED -> "验证成功，请返回浏览器继续登录。";
                case INVALID -> "验证码不正确，请核对 WAO 登录页上的 4 位数字后重试。";
                case NO_ACTIVE_SCAN -> "请先在 WAO 登录页扫描公众号二维码，再发送页面验证码。";
            };
        } else {
            return "success";
        }
        return replyXml(message.fromUser(), message.toUser(), reply);
    }

    /** 读取已扫码关注用户的真实资料；资料权限不足时仍使用回调已验证的 Open ID。 */
    public OfficialProfile profile(String openId) {
        WechatOfficialAccountConfigService.Effective effective = requireEnabled();
        if (!StringUtils.hasText(openId)) throw new WechatLoginException("微信公众号没有返回有效用户身份");
        try {
            UserResponse user = fetchUser(accessToken(effective), openId);
            if (tokenInvalid(user == null ? null : user.errcode())) {
                store.clearAccessToken();
                user = fetchUser(accessToken(effective), openId);
            }
            if (user != null && StringUtils.hasText(user.openId())) {
                return new OfficialProfile(officialSubject(effective.appId(), user.openId()), user.unionId(),
                        user.nickname(), user.headImageUrl());
            }
        } catch (RestClientException | WechatLoginException exception) {
            log.warn("Wechat official profile lookup failed; falling back to verified Open ID: {}",
                    exception.getClass().getSimpleName());
        }
        return new OfficialProfile(officialSubject(effective.appId(), openId), null, null, null);
    }

    private synchronized String accessToken(WechatOfficialAccountConfigService.Effective effective) {
        Optional<String> cached = store.accessToken();
        if (cached.isPresent()) return cached.get();
        try {
            TokenResponse response = client.get().uri(uri -> uri.path("/cgi-bin/token")
                    .queryParam("grant_type", "client_credential")
                    .queryParam("appid", effective.appId())
                    .queryParam("secret", effective.appSecret()).build()).retrieve().body(TokenResponse.class);
            if (response == null || !StringUtils.hasText(response.accessToken())) {
                log.warn("Wechat official access token request failed: code={}",
                        response == null ? null : response.errcode());
                throw new WechatLoginException("公众号接口认证失败，请检查 App ID、App Secret 与 IP 白名单");
            }
            store.saveAccessToken(response.accessToken(), response.expiresIn() == null ? 7200 : response.expiresIn());
            return response.accessToken();
        } catch (WechatLoginException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw new WechatLoginException("微信公众号接口暂时不可用，请稍后重试", exception);
        }
    }

    private QrResponse createQr(String accessToken, String scene) {
        try {
            return client.post().uri(uri -> uri.path("/cgi-bin/qrcode/create")
                            .queryParam("access_token", accessToken).build())
                    .body(Map.of("expire_seconds", SESSION_TTL.toSeconds(), "action_name", "QR_STR_SCENE",
                            "action_info", Map.of("scene", Map.of("scene_str", scene))))
                    .retrieve().body(QrResponse.class);
        } catch (RestClientException exception) {
            throw new WechatLoginException("微信公众号二维码接口暂时不可用，请稍后重试", exception);
        }
    }

    private UserResponse fetchUser(String accessToken, String openId) {
        return client.get().uri(uri -> uri.path("/cgi-bin/user/info")
                        .queryParam("access_token", accessToken).queryParam("openid", openId)
                        .queryParam("lang", "zh_CN").build())
                .retrieve().body(UserResponse.class);
    }

    private OfficialMessage parseMessage(String xml) {
        if (!StringUtils.hasText(xml) || xml.length() > MAX_XML_LENGTH) {
            throw new IllegalArgumentException("Invalid Wechat official account message");
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
            return new OfficialMessage(text(document, "ToUserName"), text(document, "FromUserName"),
                    text(document, "MsgType"), text(document, "Event"), text(document, "EventKey"),
                    text(document, "Content"));
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid Wechat official account message", exception);
        }
    }

    private WechatOfficialAccountConfigService.Effective requireEnabled() {
        WechatOfficialAccountConfigService.Effective effective = config.current();
        if (!effective.configured()) {
            throw new WechatLoginException("公众号登录待配置：需要认证公众号的 App ID、App Secret、Token 与消息回调");
        }
        return effective;
    }

    private String randomHex(int bytes) {
        byte[] value = new byte[bytes];
        random.nextBytes(value);
        return HexFormat.of().formatHex(value);
    }

    private String officialSubject(String appId, String openId) {
        return "official:" + appId + ":" + openId;
    }

    private static String text(Document document, String tag) {
        return document.getElementsByTagName(tag).getLength() == 0 ? null
                : document.getElementsByTagName(tag).item(0).getTextContent();
    }

    private static String replyXml(String toUser, String fromUser, String content) {
        return "<xml><ToUserName><![CDATA[" + toUser + "]]></ToUserName>"
                + "<FromUserName><![CDATA[" + fromUser + "]]></FromUserName>"
                + "<CreateTime>" + Instant.now().getEpochSecond() + "</CreateTime>"
                + "<MsgType><![CDATA[text]]></MsgType><Content><![CDATA[" + content + "]]></Content></xml>";
    }

    private static boolean tokenInvalid(Integer code) { return code != null && (code == 40014 || code == 42001); }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    private record OfficialMessage(String toUser, String fromUser, String messageType, String event,
                                   String eventKey, String content) { }
    private record TokenResponse(@JsonProperty("access_token") String accessToken,
                                 @JsonProperty("expires_in") Long expiresIn, Integer errcode, String errmsg) { }
    private record QrResponse(String ticket, @JsonProperty("expire_seconds") Long expiresIn,
                              String url, Integer errcode, String errmsg) { }
    private record UserResponse(@JsonProperty("openid") String openId, @JsonProperty("unionid") String unionId,
                                String nickname, @JsonProperty("headimgurl") String headImageUrl,
                                Integer errcode, String errmsg) { }

    public record OfficialProfile(String userId, String unionId, String name, String avatarUrl) { }
}
