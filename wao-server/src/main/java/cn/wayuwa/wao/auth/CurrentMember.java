package cn.wayuwa.wao.auth;

import cn.wayuwa.wao.domain.AccountEntity;
import cn.wayuwa.wao.mapper.AccountMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * 把「当前服务端会话」翻译成「当前组织成员 id」。
 *
 * <p>之前写库时作者一律硬编码成 {@code wg}，于是谁建的文档署名都是娃哥。作者必须由
 * 服务端从会话推导，不能由请求体里带上来 —— 否则任何人都能冒名写入。
 */
@Component
public class CurrentMember {

    private final AuthSessionManager sessions;
    private final AccountMapper accountMapper;

    public CurrentMember(AuthSessionManager sessions, AccountMapper accountMapper) {
        this.sessions = sessions;
        this.accountMapper = accountMapper;
    }

    /**
     * 返回当前登录成员 id。
     *
     * @param request 当前请求
     * @return 成员 id；未登录或账号已被清理时返回 null（调用方自行决定兜底）
     */
    public String id(HttpServletRequest request) {
        String accountId = sessions.currentAccountId(request);
        if (accountId == null) {
            return null;
        }
        AccountEntity account = accountMapper.selectById(accountId);
        return account == null ? null : account.getMemberId();
    }

    /**
     * 返回当前登录成员 id，未登录时回落到给定值。
     *
     * @param request  当前请求
     * @param fallback 兜底成员 id
     * @return 成员 id
     */
    public String idOr(HttpServletRequest request, String fallback) {
        String id = id(request);
        return id == null ? fallback : id;
    }
}
