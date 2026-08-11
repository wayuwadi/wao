package cn.wayuwa.wao.agent.platform.douyin;

import cn.wayuwa.wao.agent.common.BadRequestException;
import cn.wayuwa.wao.agent.platform.MediaKind;
import cn.wayuwa.wao.agent.platform.PlatformCapability;
import cn.wayuwa.wao.agent.platform.PlatformCredential;
import cn.wayuwa.wao.agent.platform.PlatformIdentity;
import cn.wayuwa.wao.agent.platform.PlatformType;
import cn.wayuwa.wao.agent.platform.PublishPlatform;
import cn.wayuwa.wao.agent.platform.PublishRequest;
import cn.wayuwa.wao.agent.platform.PublishResult;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 抖音开放平台适配器。
 *
 * <p>接口参考：授权 {@code /oauth/access_token/}、刷新 {@code /oauth/refresh_token/}、
 * 上传 {@code /api/douyin/v1/video/upload_video/}、创建 {@code /api/douyin/v1/video/create_video/}。
 * 发布相关接口需要 {@code video.create.bind} 权限，该权限要在开放平台单独申请并通过审核。
 */
@Component
public class DouyinPublishPlatform implements PublishPlatform {
    private static final Logger log = LoggerFactory.getLogger(DouyinPublishPlatform.class);
    /** 抖音单应用单用户每天最多 75 条，超过由平台直接拒绝。 */
    private static final long MAX_VIDEO_BYTES = 300L * 1024 * 1024;

    private final DouyinProperties properties;
    private final RestClient restClient;

    public DouyinPublishPlatform(DouyinProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        this.restClient = builder.build();
    }

    @Override
    public PlatformType type() {
        return PlatformType.DOUYIN;
    }

    @Override
    public PlatformCapability capability() {
        if (!properties.configured()) {
            return PlatformCapability.planned("抖音开放平台应用尚未配置。需要在 open.douyin.com 创建应用、"
                    + "完成企业开发者认证并申请 video.create.bind 权限，再把 client_key/client_secret 配到节点环境变量。");
        }
        return new PlatformCapability(true, true, List.of(MediaKind.VIDEO),
                "已接入抖音开放平台。视频不超过 300MB、时长不超过 15 分钟；发布额度由抖音按每用户每天 75 条限制。");
    }

    @Override
    public String authorizeUrl(String state) {
        requireConfigured();
        return properties.base() + "/platform/oauth/connect/"
                + "?client_key=" + encode(properties.clientKey())
                + "&response_type=code"
                + "&scope=" + encode(properties.scope())
                + "&redirect_uri=" + encode(properties.redirectUri())
                + "&state=" + encode(state);
    }

    @Override
    public PlatformIdentity exchangeCode(String code) {
        requireConfigured();
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_key", properties.clientKey());
        form.add("client_secret", properties.clientSecret());
        form.add("code", code);
        form.add("grant_type", "authorization_code");
        JsonNode data = postForm("/oauth/access_token/", form);
        return toIdentity(data);
    }

    @Override
    public PlatformIdentity refresh(PlatformCredential credential) {
        requireConfigured();
        if (!StringUtils.hasText(credential.refreshToken())) {
            throw new BadRequestException("抖音账号缺少 refresh_token，请重新授权");
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_key", properties.clientKey());
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", credential.refreshToken());
        JsonNode data = postForm("/oauth/refresh_token/", form);
        PlatformIdentity refreshed = toIdentity(data);
        // 刷新接口不返回昵称，沿用已保存的展示信息由调用方决定。
        return refreshed;
    }

    @Override
    public PublishResult publish(PlatformCredential credential, PublishRequest request) {
        requireConfigured();
        if (!MediaKind.VIDEO.equals(request.mediaKind())) {
            throw new BadRequestException("抖音当前只接入了视频发布，请上传视频素材");
        }
        if (request.mediaPath() == null) {
            throw new BadRequestException("发布抖音视频前必须上传视频素材");
        }
        FileSystemResource file = new FileSystemResource(request.mediaPath());
        if (!file.exists()) {
            throw new BadRequestException("视频素材文件不存在，请重新上传");
        }
        if (file.getFile().length() > MAX_VIDEO_BYTES) {
            throw new BadRequestException("视频超过 300MB，需要改用抖音分片上传接口，当前版本尚未接入");
        }

        String videoId = uploadVideo(credential, file, request.mediaName());
        String itemId = createVideo(credential, videoId, request.captionWithTags());
        log.info("抖音发布成功 open_id={} item_id={}", credential.platformUserId(), itemId);
        return new PublishResult(itemId, null);
    }

    private String uploadVideo(PlatformCredential credential, FileSystemResource file, String fileName) {
        String name = fileName == null || fileName.isBlank() ? "video.mp4" : fileName;
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("video", new FileSystemResource(file.getFile()) {
            @Override
            public String getFilename() {
                return name;
            }
        });
        JsonNode data = exchange("/api/douyin/v1/video/upload_video/?open_id="
                + encode(credential.platformUserId()), credential.accessToken(),
                MediaType.MULTIPART_FORM_DATA, body);
        String videoId = data.path("video").path("video_id").asText(null);
        if (!StringUtils.hasText(videoId)) {
            throw new BadRequestException("抖音上传视频未返回 video_id");
        }
        return videoId;
    }

    private String createVideo(PlatformCredential credential, String videoId, String text) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("video_id", videoId);
        payload.put("text", text.length() > 1000 ? text.substring(0, 1000) : text);
        JsonNode data = exchange("/api/douyin/v1/video/create_video/?open_id="
                + encode(credential.platformUserId()), credential.accessToken(),
                MediaType.APPLICATION_JSON, payload);
        String itemId = data.path("item_id").asText(null);
        if (!StringUtils.hasText(itemId)) {
            throw new BadRequestException("抖音创建视频未返回 item_id");
        }
        return itemId;
    }

    /** 调用需要用户令牌的业务接口，并展开 {@code data.error_code}。 */
    private JsonNode exchange(String path, String accessToken, MediaType contentType, Object body) {
        JsonNode json;
        try {
            json = restClient.post().uri(properties.base() + path)
                    .header("access-token", accessToken)
                    .contentType(contentType)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException ex) {
            throw new BadRequestException("调用抖音接口失败：" + ex.getMessage());
        }
        return requireSuccess(json, path);
    }

    private JsonNode postForm(String path, MultiValueMap<String, String> form) {
        JsonNode json;
        try {
            json = restClient.post().uri(properties.base() + path)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException ex) {
            throw new BadRequestException("调用抖音接口失败：" + ex.getMessage());
        }
        return requireSuccess(json, path);
    }

    /** 抖音把业务错误放在 HTTP 200 的 {@code data.error_code} 里，必须显式检查。 */
    private JsonNode requireSuccess(JsonNode json, String path) {
        if (json == null || json.path("data").isMissingNode()) {
            throw new BadRequestException("抖音接口返回格式不符合预期：" + path);
        }
        JsonNode data = json.path("data");
        int errorCode = data.path("error_code").asInt(0);
        if (errorCode != 0) {
            String description = data.path("description").asText("");
            throw new BadRequestException("抖音接口返回错误 " + errorCode
                    + (description.isBlank() ? "" : "：" + description));
        }
        return data;
    }

    private PlatformIdentity toIdentity(JsonNode data) {
        String openId = data.path("open_id").asText(null);
        String accessToken = data.path("access_token").asText(null);
        if (!StringUtils.hasText(openId) || !StringUtils.hasText(accessToken)) {
            throw new BadRequestException("抖音授权响应缺少 open_id 或 access_token");
        }
        LocalDateTime now = LocalDateTime.now();
        PlatformCredential credential = new PlatformCredential(openId, accessToken,
                data.path("refresh_token").asText(null), data.path("scope").asText(null),
                now.plusSeconds(data.path("expires_in").asLong(0)),
                now.plusSeconds(data.path("refresh_expires_in").asLong(0)));
        return new PlatformIdentity("抖音用户 " + shortId(openId), null, credential);
    }

    private String shortId(String openId) {
        return openId.length() <= 6 ? openId : openId.substring(openId.length() - 6);
    }

    private void requireConfigured() {
        if (!properties.configured()) {
            throw new BadRequestException(capability().note());
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
