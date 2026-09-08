package cn.wayuwa.wao.feishu;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 飞书开放平台自建应用客户端：换 tenant_access_token、列群、列群成员、拉消息历史、下载消息里的图片。
 *
 * <p>凭据不来自配置文件，而是每次由调用方从 {@link cn.wayuwa.wao.service.SettingService} 取出后传进来 ——
 * App ID / Secret 由用户在「自动化 → 飞书群聊同步」页面里填，加密存库，不进 Git 也不进环境变量。
 *
 * <p>token 按 appId 在进程内缓存到过期前 5 分钟，避免每条消息、每张图片都换一次令牌。
 */
@Component
public class FeishuOpenApiClient {

    /** 单次拉取的分页大小；飞书消息接口上限 50。 */
    private static final int PAGE_SIZE = 50;
    /** 一次同步最多翻多少页，防止异常数据把定时任务拖死。 */
    private static final int MAX_PAGES = 40;

    private final RestClient client;
    private final Map<String, CachedToken> tokens = new java.util.concurrent.ConcurrentHashMap<>();

    @Autowired
    public FeishuOpenApiClient(RestClient.Builder builder,
                               @Value("${feishu.login.open-api-base-url:https://open.feishu.cn}") String baseUrl) {
        this(builder, baseUrl, requestFactory());
    }

    FeishuOpenApiClient(RestClient.Builder builder, String baseUrl, ClientHttpRequestFactory requestFactory) {
        if (requestFactory != null) builder.requestFactory(requestFactory);
        this.client = builder.baseUrl(baseUrl).build();
    }

    private static ClientHttpRequestFactory requestFactory() {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(Duration.ofSeconds(30));
        return factory;
    }

    /**
     * 取 tenant_access_token（自建应用），命中缓存则直接返回。
     *
     * @param appId     应用 App ID
     * @param appSecret 应用 App Secret
     * @return 可用于 Authorization 头的令牌
     * @throws FeishuApiException 凭据不对或接口不可达时
     */
    public String tenantAccessToken(String appId, String appSecret) {
        CachedToken cached = tokens.get(appId);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
            return cached.token();
        }
        JsonNode res = post("/open-apis/auth/v3/tenant_access_token/internal",
                Map.of("app_id", appId, "app_secret", appSecret), null);
        String token = text(res, "tenant_access_token");
        if (token.isBlank()) {
            throw new FeishuApiException("换取飞书 tenant_access_token 失败：" + text(res, "msg"));
        }
        long expire = res.path("expire").asLong(7200);
        tokens.put(appId, new CachedToken(token, Instant.now().plusSeconds(Math.max(60, expire)).minus(Duration.ofMinutes(5))));
        return token;
    }

    /**
     * 列出机器人所在的全部群。
     *
     * @param token tenant_access_token
     * @return 群列表（chatId + 名称）
     */
    public List<FeishuChat> listChats(String token) {
        List<FeishuChat> out = new ArrayList<>();
        String pageToken = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            JsonNode data = pageToken == null
                    ? get("/open-apis/im/v1/chats?page_size={pageSize}", token, PAGE_SIZE)
                    : get("/open-apis/im/v1/chats?page_size={pageSize}&page_token={pageToken}",
                            token, PAGE_SIZE, pageToken);
            for (JsonNode item : data.path("items")) {
                out.add(new FeishuChat(text(item, "chat_id"), text(item, "name")));
            }
            pageToken = nextPageToken(data);
            if (pageToken == null) {
                break;
            }
        }
        return out;
    }

    /**
     * 列出某个群的成员（用于把发言人 ID 对上人名）。
     *
     * @param token  tenant_access_token
     * @param chatId 群 id
     * @return 成员列表（open_id + 飞书昵称）
     */
    public List<FeishuChatMember> listChatMembers(String token, String chatId) {
        return listChatMembers(token, chatId, "open_id");
    }

    /**
     * 把机器人应用下的 Open ID 换成同开发商稳定的 Union ID。
     *
     * <p>飞书的群成员接口一次只能选择一种 ID 类型，因此分别读取 Open ID 和
     * Union ID 列表，再以群内唯一昵称配对。任一列表出现重名就拒绝换算，不猜人。
     *
     * @param token tenant_access_token
     * @param chatId 群 id
     * @param openId 机器人应用下的成员 Open ID
     * @return 唯一对应的 Union ID；无法安全配对时返回空串
     */
    public String chatMemberUnionId(String token, String chatId, String openId) {
        if (openId == null || openId.isBlank()) {
            return "";
        }
        List<FeishuChatMember> openMembers = listChatMembers(token, chatId, "open_id");
        FeishuChatMember source = openMembers.stream()
                .filter(member -> openId.equals(member.openId())).findFirst().orElse(null);
        if (source == null || source.name().isBlank()
                || openMembers.stream().filter(member -> source.name().equals(member.name())).count() != 1) {
            return "";
        }
        List<FeishuChatMember> unionMembers = listChatMembers(token, chatId, "union_id");
        List<FeishuChatMember> matches = unionMembers.stream()
                .filter(member -> source.name().equals(member.name())).toList();
        return matches.size() == 1 ? matches.get(0).openId() : "";
    }

    /**
     * 把同开发商稳定的 Union ID 换成当前群机器人应用可用于 @ 的 Open ID。
     *
     * <p>飞书没有提供直接转换端点；这里分别读取两种 id_type 的群成员，并且只有昵称在两份列表中
     * 都唯一时才配对。重名、已退群或未保存 Union ID 时返回空串，绝不猜测要 @ 的人。
     *
     * @param token tenant_access_token
     * @param chatId 目标群 id
     * @param unionId 当前成员在同开发商下的 Union ID
     * @return 当前应用下的 Open ID；无法唯一映射时为空串
     */
    public String chatMemberOpenIdByUnionId(String token, String chatId, String unionId) {
        if (unionId == null || unionId.isBlank()) return "";
        List<FeishuChatMember> unionMembers = listChatMembers(token, chatId, "union_id");
        FeishuChatMember source = unionMembers.stream()
                .filter(member -> unionId.equals(member.openId())).findFirst().orElse(null);
        if (source == null || source.name().isBlank()
                || unionMembers.stream().filter(member -> source.name().equals(member.name())).count() != 1) {
            return "";
        }
        List<FeishuChatMember> openMembers = listChatMembers(token, chatId, "open_id");
        List<FeishuChatMember> matches = openMembers.stream()
                .filter(member -> source.name().equals(member.name())).toList();
        return matches.size() == 1 ? matches.get(0).openId() : "";
    }

    private List<FeishuChatMember> listChatMembers(String token, String chatId, String memberIdType) {
        List<FeishuChatMember> out = new ArrayList<>();
        String pageToken = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            JsonNode data = pageToken == null
                    ? get("/open-apis/im/v1/chats/{chatId}/members?member_id_type={memberIdType}&page_size=100",
                            token, chatId, memberIdType)
                    : get("/open-apis/im/v1/chats/{chatId}/members?member_id_type={memberIdType}&page_size=100"
                            + "&page_token={pageToken}", token, chatId, memberIdType, pageToken);
            for (JsonNode item : data.path("items")) {
                out.add(new FeishuChatMember(text(item, "member_id"), text(item, "name")));
            }
            pageToken = nextPageToken(data);
            if (pageToken == null) {
                break;
            }
        }
        return out;
    }

    /**
     * 拉一个时间区间内的群聊消息历史（按时间正序）。
     *
     * @param token     tenant_access_token
     * @param chatId    群 id
     * @param startSecs 起始时间（秒级时间戳，含）
     * @param endSecs   结束时间（秒级时间戳，含）
     * @return 该区间内的消息，按发送时间升序
     * @throws FeishuApiException 消息超过单次同步上限，无法保证返回完整结果时
     */
    public List<FeishuMessage> listMessages(String token, String chatId, long startSecs, long endSecs) {
        List<FeishuMessage> out = new ArrayList<>();
        Set<String> seenPageTokens = new HashSet<>();
        String pageToken = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            String uri = "/open-apis/im/v1/messages?container_id_type=chat&container_id={chatId}"
                    + "&start_time={start}&end_time={end}"
                    + "&sort_type=ByCreateTimeAsc&page_size={pageSize}"
                    + (pageToken == null ? "" : "&page_token={pageToken}");
            JsonNode data = pageToken == null
                    ? get(uri, token, chatId, startSecs, endSecs, PAGE_SIZE)
                    : get(uri, token, chatId, startSecs, endSecs, PAGE_SIZE, pageToken);
            for (JsonNode item : data.path("items")) {
                List<FeishuMention> mentions = new ArrayList<>();
                for (JsonNode mention : item.path("mentions")) {
                    JsonNode mentionId = mention.path("id");
                    mentions.add(new FeishuMention(
                            text(mention, "key"),
                            mentionId.path("open_id").asText(mentionId.isTextual() ? mentionId.asText() : ""),
                            text(mention, "name")));
                }
                out.add(new FeishuMessage(
                        text(item, "message_id"),
                        item.path("sender").path("id").asText(""),
                        item.path("sender").path("sender_type").asText(""),
                        text(item, "msg_type"),
                        item.path("body").path("content").asText(""),
                        item.path("create_time").asLong(0),
                        item.path("deleted").asBoolean(false),
                        List.copyOf(mentions), text(item, "root_id"), text(item, "parent_id")));
            }
            boolean hasMore = data.path("has_more").asBoolean(false);
            if (page == MAX_PAGES - 1 && hasMore) {
                throw new FeishuApiException("已达到飞书群聊单次同步分页上限 "
                        + MAX_PAGES + " 页（每页最多 " + PAGE_SIZE
                        + " 条），但飞书仍有更多消息；已停止以避免保存不完整归档");
            }
            String nextPageToken = nextPageToken(data);
            if (hasMore && nextPageToken == null) {
                throw new FeishuApiException("飞书消息分页返回 has_more=true，但 page_token 为空；"
                        + "已停止以避免返回不完整消息");
            }
            if (nextPageToken != null && !seenPageTokens.add(nextPageToken)) {
                throw new FeishuApiException("飞书消息分页返回了重复 page_token；"
                        + "已停止以避免返回不完整消息");
            }
            pageToken = nextPageToken;
            if (pageToken == null) {
                break;
            }
        }
        return out;
    }

    /**
     * 下载消息里的图片资源。
     *
     * @param token     tenant_access_token
     * @param messageId 消息 id
     * @param fileKey   图片 key（image_key）
     * @return 图片字节；下载失败时返回 null（单张图失败不该让整次同步失败）
     */
    public byte[] downloadImage(String token, String messageId, String fileKey) {
        try {
            return client.get()
                    .uri("/open-apis/im/v1/messages/{messageId}/resources/{fileKey}?type=image", messageId, fileKey)
                    .header("Authorization", "Bearer " + token)
                    .retrieve()
                    .body(byte[].class);
        } catch (RestClientException ex) {
            return null;
        }
    }

    /**
     * 往一个群发一张交互卡片。
     *
     * <p>群聊快速收集要把「收下了」这句话回在事件发生的那个群里，而不是全局 webhook 指向的
     * 那个群 —— 用户是在哪儿说的想法，回执就该出现在哪儿。
     *
     * @param token   tenant_access_token
     * @param chatId  目标群 id
     * @param card    要发送的卡片
     * @throws FeishuApiException 接口不可达或返回错误码时
     */
    public void sendCard(String token, String chatId, FeishuWebhookNotifier.Card card) {
        post("/open-apis/im/v1/messages?receive_id_type=chat_id",
                Map.of("receive_id", chatId, "msg_type", "interactive", "content", writeJson(card.toPayload())),
                token);
    }

    /** 发送业务提供的 JSON 2.0 卡片，携带稳定 uuid 并要求真实消息回执。 */
    public String sendRawCard(String token, String chatId, JsonNode card, String uuid) {
        JsonNode data = post("/open-apis/im/v1/messages?receive_id_type=chat_id",
                Map.of("receive_id", chatId, "msg_type", "interactive", "content", writeJson(card), "uuid", uuid), token);
        String messageId = text(data, "message_id");
        if (messageId == null || messageId.isBlank()) throw new FeishuApiException("飞书未返回消息回执");
        return messageId;
    }

    /**
     * 以当前机器人身份给一条消息添加表情回复。
     *
     * @param token tenant_access_token
     * @param messageId 原消息 id
     * @param emojiType 飞书表情类型，如 {@code DONE}
     * @throws FeishuApiException 接口不可达或返回错误码时
     */
    public String addMessageReaction(String token, String messageId, String emojiType) {
        JsonNode data = post("/open-apis/im/v1/messages/{messageId}/reactions",
                Map.of("reaction_type", Map.of("emoji_type", emojiType)), token, messageId);
        return text(data, "reaction_id");
    }

    /**
     * 读取一条消息上的指定表情回复。
     *
     * <p>该接口使用 tenant_access_token 鉴权，作为事件回调之外的可靠兜底。调用方仍须校验
     * 原消息、操作人身份和持久去重，不能只凭一个表情字符串派工。
     *
     * @param token tenant_access_token
     * @param messageId 原消息 id
     * @param emojiType 要读取的飞书表情类型
     * @return 当前仍存在的表情回复；操作人按跨应用稳定的 union_id 返回
     */
    public List<FeishuMessageReaction> listMessageReactions(String token, String messageId,
            String emojiType) {
        List<FeishuMessageReaction> out = new ArrayList<>();
        Set<String> seenPageTokens = new HashSet<>();
        String pageToken = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            String uri = "/open-apis/im/v1/messages/{messageId}/reactions"
                    + "?reaction_type={reactionType}&page_size={pageSize}&user_id_type=union_id"
                    + (pageToken == null ? "" : "&page_token={pageToken}");
            JsonNode data = pageToken == null
                    ? get(uri, token, messageId, emojiType, PAGE_SIZE)
                    : get(uri, token, messageId, emojiType, PAGE_SIZE, pageToken);
            for (JsonNode item : data.path("items")) {
                JsonNode operator = item.path("operator");
                out.add(new FeishuMessageReaction(
                        text(item, "reaction_id"),
                        text(operator, "operator_id"),
                        text(operator, "operator_type"),
                        text(item, "action_time"),
                        item.path("reaction_type").path("emoji_type").asText("")));
            }
            boolean hasMore = data.path("has_more").asBoolean(false);
            if (page == MAX_PAGES - 1 && hasMore) {
                throw new FeishuApiException("已达到飞书表情分页上限 " + MAX_PAGES
                        + " 页，但飞书仍有更多表情；已停止避免遗漏派工事实");
            }
            String nextPageToken = nextPageToken(data);
            if (hasMore && nextPageToken == null) {
                throw new FeishuApiException("飞书表情分页返回 has_more=true，但 page_token 为空；"
                        + "已停止避免遗漏派工事实");
            }
            if (nextPageToken != null && !seenPageTokens.add(nextPageToken)) {
                throw new FeishuApiException("飞书表情分页返回了重复 page_token；已停止避免重复派工");
            }
            pageToken = nextPageToken;
            if (pageToken == null) break;
        }
        return out;
    }

    /** 删除当前应用创建的一次表情回复。 */
    public void deleteMessageReaction(String token, String messageId, String reactionId) {
        delete("/open-apis/im/v1/messages/{messageId}/reactions/{reactionId}", token,
                messageId, reactionId);
    }

    /**
     * 发送普通飞书富文本消息；传 parentMessageId 时按飞书原生回复关系发送。
     * uuid 使用 WAO 消息 id，网络超时重试不会在飞书产生第二条消息。
     */
    public String sendPostMessage(String token, String chatId, Object postContent, String uuid,
            String parentMessageId) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        if (parentMessageId == null || parentMessageId.isBlank()) body.put("receive_id", chatId);
        body.put("msg_type", "post");
        body.put("content", writeJson(postContent));
        JsonNode data = parentMessageId == null || parentMessageId.isBlank()
                ? post("/open-apis/im/v1/messages?receive_id_type=chat_id&uuid={uuid}", body,
                        token, uuid)
                : post("/open-apis/im/v1/messages/{messageId}/reply?uuid={uuid}", body,
                        token, parentMessageId, uuid);
        String messageId = text(data, "message_id");
        if (messageId.isBlank()) throw new FeishuApiException("飞书没有返回消息 id");
        return messageId;
    }

    /** 发送普通文本；传 parentMessageId 时回复原消息，uuid 保证网络重试不会重复发送。 */
    public String sendTextMessage(String token, String chatId, String text, String uuid,
            String parentMessageId) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        if (parentMessageId == null || parentMessageId.isBlank()) body.put("receive_id", chatId);
        body.put("msg_type", "text");
        body.put("content", writeJson(Map.of("text", text)));
        JsonNode data = parentMessageId == null || parentMessageId.isBlank()
                ? post("/open-apis/im/v1/messages?receive_id_type=chat_id&uuid={uuid}", body,
                        token, uuid)
                : post("/open-apis/im/v1/messages/{messageId}/reply?uuid={uuid}", body,
                        token, parentMessageId, uuid);
        String messageId = text(data, "message_id");
        if (messageId.isBlank()) throw new FeishuApiException("飞书没有返回消息 id");
        return messageId;
    }

    /** 上传一张用于普通图文消息的图片，返回可放进 post img 元素的 image_key。 */
    public String uploadMessageImage(String token, byte[] bytes, String contentType, String filename) {
        try {
            MultipartBodyBuilder body = new MultipartBodyBuilder();
            body.part("image_type", "message");
            body.part("image", new ByteArrayResource(bytes) {
                @Override public String getFilename() {
                    return filename == null || filename.isBlank() ? "image.png" : filename;
                }
            }).contentType(MediaType.parseMediaType(
                    contentType == null || contentType.isBlank() ? "image/png" : contentType));
            JsonNode res = client.post().uri("/open-apis/im/v1/images")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body.build()).retrieve().body(JsonNode.class);
            String key = text(requireOk(res), "image_key");
            if (key.isBlank()) throw new FeishuApiException("飞书没有返回图片 key");
            return key;
        } catch (RestClientException ex) {
            throw new FeishuApiException("上传飞书消息图片失败", ex);
        }
    }

    /**
     * 获取当前应用机器人的 open_id，用于主动拉消息时精确判断被 @ 的是不是本机器人。
     *
     * @param token tenant_access_token
     * @return 当前应用机器人的 open_id
     */
    public String botOpenId(String token) {
        JsonNode data = get("/open-apis/bot/v3/info", token);
        String openId = data.path("bot").path("open_id").asText("");
        if (openId.isBlank()) {
            throw new FeishuApiException("飞书没有返回当前机器人 open_id");
        }
        return openId;
    }

    /** 构造通知按钮和个人授权入口，不展示成员摘要或另发反馈消息。 */
    public Map<String, Object> feedbackCard(String title, String body, String notificationId, String ignoredSummary) {
        Map<String, Object> button = Map.of("tag", "button", "text", Map.of("tag", "plain_text", "content", "做到了"),
                "type", "primary", "value", Map.of("kind", "automation-feedback", "notificationId", notificationId, "action", "done"));
        Map<String, Object> missed = Map.of("tag", "button", "text", Map.of("tag", "plain_text", "content", "没做到"),
                "type", "danger", "value", Map.of("kind", "automation-feedback", "notificationId", notificationId, "action", "missed"));
        return Map.of("config", Map.of("wide_screen_mode", true, "update_multi", true),
                "header", Map.of("template", "blue", "title", Map.of("tag", "plain_text", "content", title)),
                "elements", List.of(Map.of("tag", "div", "text", Map.of("tag", "lark_md", "content", body == null ? "" : body)),
                        Map.of("tag", "action", "actions", List.of(button, missed)),
                        Map.of("tag", "note", "elements", List.of(Map.of("tag", "lark_md", "content",
                                "首次使用：[授权个人表情](https://wao.wayuwa.cn/api/auth/feishu/reactions)，让表情显示为你本人。")))));
    }

    /** 改选时移除本人旧表情；已被本人手工取消（231011）视为完成，其余错误仍失败。 */
    public void deleteOwnFeedbackReaction(String token, String messageId, String reactionId) {
        try {
            client.delete().uri("/open-apis/im/v1/messages/{messageId}/reactions/{reactionId}", messageId, reactionId)
                    .header("Authorization", "Bearer " + token).exchange((request, response) -> {
                        JsonNode result = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.getBody());
                        if (result != null && result.path("code").asInt() == 231011) return null;
                        if (response.getStatusCode().isError()) throw new FeishuApiException("删除本人旧表情失败");
                        requireOk(result); return null;
                    });
        } catch (RestClientException ex) { throw new FeishuApiException("删除本人旧表情失败"); }
    }

    /** 使用个人凭证贴表情并核对飞书回执作者；绝不接受机器人回执冒充成员。 */
    public String addUserMessageReaction(String token, String messageId, String emojiType, String expectedOpenId) {
        JsonNode data = post("/open-apis/im/v1/messages/{messageId}/reactions",
                Map.of("reaction_type", Map.of("emoji_type", emojiType)), token, messageId);
        String reactionId = data.path("reaction_id").asText("");
        if (!"user".equals(data.path("operator").path("operator_type").asText())
                || !expectedOpenId.equals(data.path("operator").path("operator_id").asText()) || reactionId.isBlank()) {
            if (!reactionId.isBlank()) deleteMessageReaction(token, messageId, reactionId);
            throw new FeishuApiException("表情作者与点击者不一致，请重新授权个人表情");
        }
        return reactionId;
    }

    /** 发送共享反馈卡，要求消息回执用于后续身份校验和原卡更新。 */
    public String sendFeedbackCard(String token, String chatId, String title, String body, String notificationId) {
        JsonNode data = post("/open-apis/im/v1/messages?receive_id_type=chat_id",
                Map.of("receive_id", chatId, "msg_type", "interactive", "uuid", notificationId,
                        "content", writeJson(feedbackCard(title, body, notificationId, ""))), token);
        return requireMessageId(data);
    }

    /** 原地更新所有成员可见的共享卡片，飞书业务失败抛出异常。 */
    public void updateFeedbackCard(String token, String messageId, Map<String, Object> card) {
        try {
            JsonNode res = client.patch().uri("/open-apis/im/v1/messages/{messageId}", messageId)
                    .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("content", writeJson(card))).retrieve().body(JsonNode.class);
            requireOk(res);
        } catch (RestClientException ex) {
            throw new FeishuApiException("更新通知反馈卡片失败", ex);
        }
    }

    /** 旧非共享通知以原消息下的共享回复承载反馈，稳定 uuid 避免重试重复发送。 */
    public String replyFeedbackCard(String token, String messageId, Map<String, Object> card, String uuid) {
        return requireMessageId(post("/open-apis/im/v1/messages/{messageId}/reply",
                Map.of("msg_type", "interactive", "content", writeJson(card), "uuid", uuid), token, messageId));
    }

    private String requireMessageId(JsonNode data) {
        String messageId = text(data, "message_id");
        if (messageId == null || messageId.isBlank()) throw new FeishuApiException("飞书未返回消息回执");
        return messageId;
    }

    /** 往一个成员的飞书私聊发送带 WAO 跳转按钮的行动提醒。 */
    public void sendDirectCard(String token, String openId, String title, String body, String actionUrl) {
        sendDirectCard(token, openId, title, body, actionUrl, "打开 WAO 打卡");
    }

    /** 往一个成员的飞书私聊发送带自定义 WAO 跳转按钮的提醒。 */
    public void sendDirectCard(String token, String openId, String title, String body, String actionUrl,
            String actionLabel) {
        Map<String, Object> card = Map.of(
                "config", Map.of("wide_screen_mode", true),
                "header", Map.of("template", "turquoise",
                        "title", Map.of("tag", "plain_text", "content", title)),
                "elements", List.of(
                        Map.of("tag", "div", "text", Map.of("tag", "lark_md", "content", body)),
                        Map.of("tag", "action", "actions", List.of(Map.of(
                                "tag", "button", "type", "primary",
                                "text", Map.of("tag", "plain_text", "content", actionLabel),
                                "url", actionUrl)))));
        post("/open-apis/im/v1/messages?receive_id_type=open_id",
                Map.of("receive_id", openId, "msg_type", "interactive", "content", writeJson(card)), token);
    }

    private String writeJson(Object value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (Exception ex) {
            throw new FeishuApiException("卡片内容序列化失败", ex);
        }
    }

    private JsonNode get(String uri, String token, Object... uriVariables) {
        try {
            JsonNode res = client.get().uri(uri, uriVariables).header("Authorization", "Bearer " + token)
                    .retrieve().body(JsonNode.class);
            return requireOk(res);
        } catch (RestClientException ex) {
            throw new FeishuApiException("调用飞书接口失败：" + uri, ex);
        }
    }

    private JsonNode post(String uri, Object body, String token, Object... uriVariables) {
        try {
            RestClient.RequestBodySpec spec = client.post().uri(uri, uriVariables)
                    .contentType(MediaType.APPLICATION_JSON);
            if (token != null) {
                spec = spec.header("Authorization", "Bearer " + token);
            }
            JsonNode res = spec.body(body).retrieve().body(JsonNode.class);
            return requireOk(res);
        } catch (RestClientException ex) {
            throw new FeishuApiException("调用飞书接口失败：" + uri, ex);
        }
    }

    private void delete(String uri, String token, Object... uriVariables) {
        try {
            JsonNode res = client.delete().uri(uri, uriVariables)
                    .header("Authorization", "Bearer " + token)
                    .retrieve().body(JsonNode.class);
            requireOk(res);
        } catch (RestClientException ex) {
            throw new FeishuApiException("调用飞书接口失败：" + uri, ex);
        }
    }

    /** 飞书用 HTTP 200 + body.code 表达业务失败，非 0 一律当异常。 */
    private JsonNode requireOk(JsonNode res) {
        if (res == null) {
            throw new FeishuApiException("飞书接口返回空响应");
        }
        int code = res.path("code").asInt(0);
        if (code != 0) {
            throw new FeishuApiException("飞书接口返回错误 " + code + "：" + text(res, "msg"));
        }
        return res.has("data") ? res.get("data") : res;
    }

    private static String nextPageToken(JsonNode data) {
        return data.path("has_more").asBoolean(false) && !data.path("page_token").asText("").isBlank()
                ? data.path("page_token").asText() : null;
    }

    private static String text(JsonNode node, String field) {
        return node == null ? "" : node.path(field).asText("");
    }

    private record CachedToken(String token, Instant expiresAt) { }

    /** 机器人所在的一个群。 */
    public record FeishuChat(String chatId, String name) { }

    /** 群成员：open_id + 飞书昵称。 */
    public record FeishuChatMember(String openId, String name) { }

    /**
     * 一条群消息。
     *
     * @param messageId  消息 id
     * @param senderId   发送者 open_id
     * @param senderType 发送者类型（user / app）
     * @param msgType    消息类型（text / image / post / …）
     * @param content    原始 content JSON 字符串
     * @param createTime 发送时间（毫秒时间戳）
     * @param deleted    是否已撤回
     */
    public record FeishuMessage(String messageId, String senderId, String senderType,
                                String msgType, String content, long createTime, boolean deleted,
                                List<FeishuMention> mentions, String rootId, String parentId) {
        /** 兼容尚不关心回复关系的调用。 */
        public FeishuMessage(String messageId, String senderId, String senderType,
                String msgType, String content, long createTime, boolean deleted,
                List<FeishuMention> mentions) {
            this(messageId, senderId, senderType, msgType, content, createTime, deleted,
                    mentions, "", "");
        }

        /** 历史调用未读取 mention 时的兼容构造。 */
        public FeishuMessage(String messageId, String senderId, String senderType,
                String msgType, String content, long createTime, boolean deleted) {
            this(messageId, senderId, senderType, msgType, content, createTime, deleted,
                    List.of(), "", "");
        }

        /** 保持既有服务单元测与内部构造的用户消息语义。 */
        public FeishuMessage(String messageId, String senderId, String msgType, String content,
                long createTime, boolean deleted) {
            this(messageId, senderId, "user", msgType, content, createTime, deleted,
                    List.of(), "", "");
        }
    }

    /** 消息中一个 @ 对象；open_id 用来精确匹配当前应用机器人。 */
    public record FeishuMention(String key, String openId, String name) { }

    /** 一次消息表情回复；operatorUnionId 按 union_id 返回，避免不同飞书应用的 open_id 不一致。 */
    public record FeishuMessageReaction(String reactionId, String operatorUnionId, String operatorType,
            String actionTime, String emojiType) { }
}
