package cn.wayuwa.wao.auth;

/** 一次性节点引导票据的权威存储；仅保存随机票据摘要。 */
public interface NodeTicketStore {
    record Ticket(String rootSessionId, String accountId, String targetOrigin) {}

    /** 保存票据摘要和最多六十秒的兑换窗口。 */
    void insert(String hash, Ticket ticket, long createdAt, long expiresAt);

    /** 原子消费指定目标且未过期的票据，重放或并发败者返回null。 */
    Ticket consume(String hash, String targetOrigin, long now);

    /** 返回本权威会话一分钟内签发数量，用于限制重复引导。 */
    int recentCount(String rootSessionId, long since);
}
