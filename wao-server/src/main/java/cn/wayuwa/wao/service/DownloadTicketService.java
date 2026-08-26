package cn.wayuwa.wao.service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import cn.wayuwa.wao.common.BadRequestException;
import org.springframework.stereotype.Service;

/**
 * 文件下载的人机校验：先从下载页换一张一次性票据，启用验证码时还要回答算术题。
 *
 * <p>在此之前 {@code /api/files/{id}/download} 是公开直链，谁拿到地址就能无限次拉取，
 * 一个脚本就能把出口带宽打满。现在公开链接指向前端确认页，人必须点一下，按系统设置还可能要答一道题，
 * 才换得到一张 {@value #TICKET_TTL_SECONDS} 秒内有效、**只能用一次**的票据。
 *
 * <p>状态放进程内存而非 Redis：票据活不过两分钟，节点重启或换节点最多让人重答一次题，
 * 不值得为它引入跨节点存储。两个节点各发各的票，云 Nginx 的会话保持已经足够。
 */
@Service
public class DownloadTicketService {

    /** 题目有效期：给人读题、输入的时间。 */
    private static final long CHALLENGE_TTL_SECONDS = 180;
    /** 票据有效期：答完题到浏览器真正发起下载之间的窗口。 */
    static final long TICKET_TTL_SECONDS = 120;
    /** 内存里最多存多少条待验证记录，超过就先清一遍过期的，防止被刷爆。 */
    private static final int MAX_ENTRIES = 5_000;

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Challenge> challenges = new ConcurrentHashMap<>();
    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();
    private final FileSettingsService settings;

    public DownloadTicketService(FileSettingsService settings) {
        this.settings = settings;
    }

    /**
     * 签发一次下载挑战；验证码关闭时题面为空，但挑战仍只能兑换一次。
     *
     * @param fileId 要下载的文件 id
     * @return 挑战 id、题面与验证码开关
     */
    public ChallengeDto issueChallenge(String fileId) {
        sweep();
        if (!settings.captchaEnabled()) {
            String id = UUID.randomUUID().toString();
            challenges.put(id, new Challenge(fileId, null,
                    Instant.now().plusSeconds(CHALLENGE_TTL_SECONDS)));
            return new ChallengeDto(id, "", false);
        }
        int a = 1 + random.nextInt(9);
        int b = 1 + random.nextInt(9);
        boolean add = random.nextBoolean() || a <= b;
        int answer = add ? a + b : a - b;
        String question = add ? a + " + " + b + " = ?" : a + " − " + b + " = ?";
        String id = UUID.randomUUID().toString();
        challenges.put(id, new Challenge(fileId, answer, Instant.now().plusSeconds(CHALLENGE_TTL_SECONDS)));
        return new ChallengeDto(id, question, true);
    }

    /**
     * 消费下载挑战并换一张一次性票据；启用验证码时同时校验答案。
     *
     * @param fileId      文件 id
     * @param challengeId 题目 id
     * @param answer      用户填的答案
     * @return 票据字符串，作为 {@code ?ticket=} 传给下载接口
     * @throws BadRequestException 题目过期、对不上文件或答案错误时（答错即作废，必须重新出题）
     */
    public String redeem(String fileId, String challengeId, String answer) {
        sweep();
        Challenge challenge = challengeId == null ? null : challenges.remove(challengeId);
        if (challenge == null || challenge.expiresAt().isBefore(Instant.now()) || !challenge.fileId().equals(fileId)) {
            throw new BadRequestException("验证已过期，请重新验证");
        }
        if (challenge.answer() == null) {
            return issueTicket(fileId);
        }
        int parsed;
        try {
            parsed = Integer.parseInt(answer == null ? "" : answer.trim());
        } catch (NumberFormatException ex) {
            throw new BadRequestException("答案不对，请重新验证");
        }
        if (parsed != challenge.answer()) {
            throw new BadRequestException("答案不对，请重新验证");
        }
        return issueTicket(fileId);
    }

    /**
     * 消费一张票据。校验通过即立刻作废 —— 一张票只换一次下载。
     *
     * @param fileId 文件 id
     * @param ticket 票据
     * @return 票据是否有效
     */
    public boolean consume(String fileId, String ticket) {
        if (ticket == null || ticket.isBlank()) {
            return false;
        }
        Ticket found = tickets.remove(ticket);
        return found != null && found.fileId().equals(fileId) && found.expiresAt().isAfter(Instant.now());
    }

    /** 清掉过期条目；顺带在条目过多时兜底，避免被大量无效请求撑爆内存。 */
    private void sweep() {
        Instant now = Instant.now();
        if (challenges.size() > MAX_ENTRIES) {
            challenges.clear();
        } else {
            challenges.values().removeIf(c -> c.expiresAt().isBefore(now));
        }
        if (tickets.size() > MAX_ENTRIES) {
            tickets.clear();
        } else {
            tickets.values().removeIf(t -> t.expiresAt().isBefore(now));
        }
    }

    private String issueTicket(String fileId) {
        String ticket = UUID.randomUUID().toString().replace("-", "");
        tickets.put(ticket, new Ticket(fileId, Instant.now().plusSeconds(TICKET_TTL_SECONDS)));
        return ticket;
    }

    private record Challenge(String fileId, Integer answer, Instant expiresAt) { }

    private record Ticket(String fileId, Instant expiresAt) { }

    /**
     * 一次公开下载挑战。
     *
     * @param challengeId 题目 id，兑换票据时带回来
     * @param question    题面；验证码关闭时为空
     * @param captchaEnabled 是否需要回答算术题
     */
    public record ChallengeDto(String challengeId, String question, boolean captchaEnabled) { }

    /** 票据有效期，供接口文案使用。 */
    public static Duration ticketTtl() {
        return Duration.ofSeconds(TICKET_TTL_SECONDS);
    }
}
