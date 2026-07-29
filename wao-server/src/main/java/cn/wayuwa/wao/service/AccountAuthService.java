package cn.wayuwa.wao.service;

import cn.wayuwa.wao.auth.AuthProperties;
import cn.wayuwa.wao.auth.ExternalIdentityProfile;
import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.ConflictException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.domain.AccountEntity;
import cn.wayuwa.wao.domain.AccountIdentityEntity;
import cn.wayuwa.wao.domain.MemberEntity;
import cn.wayuwa.wao.domain.PasswordCredentialEntity;
import cn.wayuwa.wao.dto.AccountIdentityDto;
import cn.wayuwa.wao.dto.AuthResolutionDto;
import cn.wayuwa.wao.dto.AuthSessionDto;
import cn.wayuwa.wao.dto.MemberDto;
import cn.wayuwa.wao.mapper.AccountIdentityMapper;
import cn.wayuwa.wao.mapper.AccountMapper;
import cn.wayuwa.wao.mapper.MemberMapper;
import cn.wayuwa.wao.mapper.PasswordCredentialMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 统一账号、密码凭据、外部身份绑定与登录解析服务。 */
@Service
public class AccountAuthService {
    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final int LOCK_MINUTES = 15;

    private final AccountMapper accountMapper;
    private final AccountIdentityMapper identityMapper;
    private final PasswordCredentialMapper credentialMapper;
    private final MemberMapper memberMapper;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties properties;

    public AccountAuthService(AccountMapper accountMapper, AccountIdentityMapper identityMapper,
                              PasswordCredentialMapper credentialMapper, MemberMapper memberMapper,
                              PasswordEncoder passwordEncoder, AuthProperties properties) {
        this.accountMapper = accountMapper;
        this.identityMapper = identityMapper;
        this.credentialMapper = credentialMapper;
        this.memberMapper = memberMapper;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    /** 返回账号及其本地成员、已绑定登录方式。 */
    public AuthSessionDto session(String accountId) {
        AccountEntity account = requireActiveAccount(accountId);
        MemberEntity member = memberMapper.selectById(account.getMemberId());
        if (member == null) throw new NotFoundException("账号关联的成员不存在");
        List<AccountIdentityDto> identities = identityMapper.findByAccountId(accountId).stream()
                .map(i -> new AccountIdentityDto(i.getProvider(), i.getEmail(), i.getDisplayName()))
                .toList();
        return new AuthSessionDto(account.getId(), toMember(member), identities, account.getCreatedAt());
    }

    /** 修改当前账号对应的真实成员资料，并返回刷新后的会话。 */
    @Transactional
    public AuthSessionDto updateProfile(String accountId, String name, String initial) {
        AccountEntity account = requireActiveAccount(accountId);
        MemberEntity member = memberMapper.selectById(account.getMemberId());
        if (member == null) throw new NotFoundException("账号关联的成员不存在");
        member.setName(name.trim());
        member.setInitial(initial.trim());
        memberMapper.updateById(member);
        return session(accountId);
    }

    /** 开发期创建邮箱账号并直接返回登录会话。 */
    @Transactional
    public AuthSessionDto register(String email, String password, String name) {
        if (!properties.registrationEnabled()) throw new BadRequestException("当前未开放自主注册，请联系管理员邀请");
        String normalized = normalizeEmail(email);
        if (identityMapper.findByProviderSubject("email", normalized) != null) {
            throw new ConflictException("该邮箱已经绑定，请直接登录");
        }
        String cleanName = name.trim();
        List<AccountEntity> matches = accountMapper.findUnclaimedByMemberName(cleanName);
        AccountEntity account = matches.size() == 1 ? matches.get(0) : createAccountAndMember(cleanName);
        bindIdentity(account.getId(), new ExternalIdentityProfile("email", normalized, normalized, cleanName, null));
        savePassword(account.getId(), password);
        return session(account.getId());
    }

    /** 校验邮箱密码、执行失败锁定并返回登录会话。 */
    @Transactional
    public AuthSessionDto login(String email, String password) {
        String normalized = normalizeEmail(email);
        AccountIdentityEntity identity = identityMapper.findByProviderSubject("email", normalized);
        if (identity == null) throw invalidCredentials();
        AccountEntity account = requireActiveAccount(identity.getAccountId());
        PasswordCredentialEntity credential = credentialMapper.selectById(account.getId());
        if (credential == null) throw invalidCredentials();
        LocalDateTime now = LocalDateTime.now();
        if (credential.getLockedUntil() != null && credential.getLockedUntil().isAfter(now)) {
            throw new UnauthorizedException("登录失败次数过多，请十五分钟后重试");
        }
        if (!passwordEncoder.matches(password, credential.getPasswordHash())) {
            int failed = (credential.getFailedAttempts() == null ? 0 : credential.getFailedAttempts()) + 1;
            credential.setFailedAttempts(failed);
            credential.setLockedUntil(failed >= MAX_FAILED_ATTEMPTS ? now.plusMinutes(LOCK_MINUTES) : null);
            credentialMapper.updateById(credential);
            throw invalidCredentials();
        }
        credential.setFailedAttempts(0);
        credential.setLockedUntil(null);
        credentialMapper.updateById(credential);
        touch(identity);
        return session(account.getId());
    }

    /** 解析已验证第三方身份：登录、绑定到当前账号或要求认领既有成员。 */
    @Transactional
    public ExternalResolution resolveExternal(ExternalIdentityProfile profile, String currentAccountId,
                                              boolean linkRequested) {
        AccountIdentityEntity existing = identityMapper.findByProviderSubject(profile.provider(), profile.subject());
        if (existing != null) {
            if (linkRequested && StringUtils.hasText(currentAccountId)
                    && !existing.getAccountId().equals(currentAccountId)) {
                throw new ConflictException("该登录身份已经绑定到另一个 WAO 账号");
            }
            touch(existing);
            return ExternalResolution.authenticated(session(existing.getAccountId()));
        }
        if (linkRequested) {
            if (!StringUtils.hasText(currentAccountId)) throw new UnauthorizedException("绑定登录方式前请先登录");
            bindIdentity(currentAccountId, profile);
            return ExternalResolution.authenticated(session(currentAccountId));
        }
        List<AccountEntity> exact = StringUtils.hasText(profile.displayName())
                ? accountMapper.findUnclaimedByMemberName(profile.displayName().trim()) : List.of();
        if (exact.size() == 1) {
            bindIdentity(exact.get(0).getId(), profile);
            return ExternalResolution.authenticated(session(exact.get(0).getId()));
        }
        List<MemberDto> candidates = accountMapper.findUnclaimed().stream()
                .map(a -> memberMapper.selectById(a.getMemberId()))
                .filter(java.util.Objects::nonNull)
                .map(this::toMember)
                .toList();
        if (candidates.isEmpty()) {
            throw new ConflictException("该身份尚未绑定，且没有可认领成员；请先使用邮箱注册或联系管理员");
        }
        return ExternalResolution.pending(profile, candidates);
    }

    /** 把本次待确认的第三方身份绑定到用户选择的尚未认领成员。 */
    @Transactional
    public AuthSessionDto claim(ExternalIdentityProfile profile, String memberId) {
        AccountEntity account = accountMapper.findByMemberId(memberId);
        if (account == null || accountMapper.findUnclaimed().stream().noneMatch(a -> a.getId().equals(account.getId()))) {
            throw new ConflictException("该成员已经被认领，请选择其他成员或联系管理员");
        }
        bindIdentity(account.getId(), profile);
        return session(account.getId());
    }

    /** 为当前账号绑定邮箱并首次设置或修改密码。 */
    @Transactional
    public AuthSessionDto updateEmailCredential(String accountId, String email, String currentPassword,
                                                String newPassword) {
        requireActiveAccount(accountId);
        String normalized = normalizeEmail(email);
        AccountIdentityEntity target = identityMapper.findByProviderSubject("email", normalized);
        if (target != null && !target.getAccountId().equals(accountId)) {
            throw new ConflictException("该邮箱已经属于另一个 WAO 账号");
        }
        PasswordCredentialEntity credential = credentialMapper.selectById(accountId);
        if (credential != null && (!StringUtils.hasText(currentPassword)
                || !passwordEncoder.matches(currentPassword, credential.getPasswordHash()))) {
            throw new UnauthorizedException("当前密码不正确");
        }
        AccountIdentityEntity current = identityMapper.findByAccountProvider(accountId, "email");
        if (current == null) {
            bindIdentity(accountId, new ExternalIdentityProfile("email", normalized, normalized, normalized, null));
        } else {
            current.setProviderSubject(normalized);
            current.setEmail(normalized);
            current.setLastLoginAt(LocalDateTime.now());
            identityMapper.updateById(current);
        }
        savePassword(accountId, newPassword);
        return session(accountId);
    }

    /**
     * 管理员为新成员开账号：新建成员 + 账号，绑定邮箱身份并设置初始密码。
     *
     * @param operatorMemberId 操作人的成员 id，必须是管理员
     * @param name             新成员姓名
     * @param email            登录邮箱
     * @param password         初始密码（8–128 字符）
     * @return 新成员资料
     * @throws UnauthorizedException 操作人不是管理员时
     * @throws ConflictException     邮箱已被绑定时
     */
    @Transactional
    public MemberDto adminCreateAccount(String operatorMemberId, String name, String email, String password) {
        requireAdmin(operatorMemberId);
        String normalized = normalizeEmail(email);
        if (identityMapper.findByProviderSubject("email", normalized) != null) {
            throw new ConflictException("该邮箱已经绑定到其他账号");
        }
        AccountEntity account = createAccountAndMember(name.trim());
        bindIdentity(account.getId(), new ExternalIdentityProfile("email", normalized, normalized, name.trim(), null));
        savePassword(account.getId(), password);
        MemberEntity member = memberMapper.selectById(account.getMemberId());
        return toMember(member);
    }

    /** 管理员重置成员的本地登录密码，并解除该账号的临时锁定。 */
    @Transactional
    public void adminResetPassword(String operatorMemberId, String memberId, String password) {
        requireAdmin(operatorMemberId);
        AccountEntity account = accountMapper.findByMemberId(memberId);
        if (account == null || !"ACTIVE".equals(account.getStatus())) throw new NotFoundException("成员账号不存在");
        savePassword(account.getId(), password);
    }

    /** 超级管理员调整普通成员与管理员角色，超级管理员身份不可由此接口变更。 */
    @Transactional
    public MemberDto updateMemberRole(String operatorMemberId, String memberId, String role) {
        requireSuperAdmin(operatorMemberId);
        if (!"管理员".equals(role) && !"成员".equals(role)) {
            throw new BadRequestException("角色只能是管理员或成员");
        }
        MemberEntity member = memberMapper.selectById(memberId);
        if (member == null) throw new NotFoundException("成员不存在");
        if ("超级管理员".equals(member.getRole())) {
            throw new BadRequestException("超级管理员权限不能在此处修改");
        }
        member.setRole(role);
        memberMapper.updateById(member);
        return toMember(member);
    }

    /** 管理员删除成员账号、身份绑定与成员目录记录；禁止删除自己和最后一名管理员。 */
    @Transactional
    public void adminDeleteAccount(String operatorMemberId, String memberId) {
        MemberEntity operator = requireAdmin(operatorMemberId);
        if (operatorMemberId.equals(memberId)) throw new BadRequestException("不能删除当前登录的管理员账号");
        MemberEntity member = memberMapper.selectById(memberId);
        if (member == null) throw new NotFoundException("成员不存在");
        if ("管理员".equals(member.getRole()) && memberMapper.selectList(null).stream()
                .filter(item -> "管理员".equals(item.getRole())).count() <= 1) {
            throw new BadRequestException("至少保留一名管理员");
        }
        AccountEntity account = accountMapper.findByMemberId(memberId);
        if (account != null) {
            identityMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AccountIdentityEntity>()
                    .eq(AccountIdentityEntity::getAccountId, account.getId()));
            credentialMapper.deleteById(account.getId());
            accountMapper.deleteById(account.getId());
        }
        memberMapper.deleteById(memberId);
    }

    private MemberEntity requireAdmin(String operatorMemberId) {
        MemberEntity operator = operatorMemberId == null ? null : memberMapper.selectById(operatorMemberId);
        if (operator == null || (!"管理员".equals(operator.getRole()) && !"超级管理员".equals(operator.getRole()))) {
            throw new UnauthorizedException("只有管理员可以管理账号");
        }
        return operator;
    }

    private MemberEntity requireSuperAdmin(String operatorMemberId) {
        MemberEntity operator = operatorMemberId == null ? null : memberMapper.selectById(operatorMemberId);
        if (operator == null || !"超级管理员".equals(operator.getRole())) {
            throw new UnauthorizedException("只有超级管理员可以调整管理员权限");
        }
        return operator;
    }

    /** 解除一种登录身份；至少保留另一种可用登录方式。 */
    @Transactional
    public AuthSessionDto unlink(String accountId, String provider) {
        requireActiveAccount(accountId);
        List<AccountIdentityEntity> identities = identityMapper.findByAccountId(accountId);
        if (identities.size() <= 1) throw new BadRequestException("至少保留一种登录方式");
        AccountIdentityEntity identity = identityMapper.findByAccountProvider(accountId, provider);
        if (identity == null) throw new NotFoundException("该登录方式尚未绑定");
        identityMapper.deleteById(identity.getId());
        if ("email".equals(provider)) credentialMapper.deleteById(accountId);
        return session(accountId);
    }

    private void bindIdentity(String accountId, ExternalIdentityProfile profile) {
        AccountIdentityEntity byAccount = identityMapper.findByAccountProvider(accountId, profile.provider());
        if (byAccount != null) throw new ConflictException("当前账号已经绑定该类型的登录方式");
        AccountIdentityEntity identity = new AccountIdentityEntity();
        identity.setId(UUID.randomUUID().toString());
        identity.setAccountId(accountId);
        identity.setProvider(profile.provider());
        identity.setProviderSubject(profile.subject());
        identity.setEmail(profile.email());
        identity.setDisplayName(profile.displayName());
        identity.setAvatarUrl(profile.avatarUrl());
        identity.setCreatedAt(LocalDateTime.now());
        identity.setLastLoginAt(LocalDateTime.now());
        identityMapper.insert(identity);
    }

    private void savePassword(String accountId, String password) {
        if (password == null || password.length() < 8 || password.length() > 128) {
            throw new BadRequestException("密码长度必须为 8–128 个字符");
        }
        PasswordCredentialEntity credential = credentialMapper.selectById(accountId);
        boolean create = credential == null;
        if (create) {
            credential = new PasswordCredentialEntity();
            credential.setAccountId(accountId);
        }
        credential.setPasswordHash(passwordEncoder.encode(password));
        credential.setFailedAttempts(0);
        credential.setLockedUntil(null);
        credential.setPasswordChangedAt(LocalDateTime.now());
        if (create) credentialMapper.insert(credential); else credentialMapper.updateById(credential);
    }

    private AccountEntity createAccountAndMember(String name) {
        String memberId = "m" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        MemberEntity member = new MemberEntity();
        member.setId(memberId);
        member.setName(name);
        member.setInitial(name.substring(0, 1));
        member.setColor("#4358D6");
        member.setRole("成员");
        memberMapper.insert(member);
        AccountEntity account = new AccountEntity();
        account.setId(UUID.randomUUID().toString());
        account.setMemberId(memberId);
        account.setStatus("ACTIVE");
        account.setCreatedAt(LocalDateTime.now());
        account.setUpdatedAt(LocalDateTime.now());
        accountMapper.insert(account);
        return account;
    }

    private AccountEntity requireActiveAccount(String accountId) {
        AccountEntity account = accountMapper.selectById(accountId);
        if (account == null || !"ACTIVE".equals(account.getStatus())) {
            throw new UnauthorizedException("账号不存在或已停用");
        }
        return account;
    }

    private void touch(AccountIdentityEntity identity) {
        identity.setLastLoginAt(LocalDateTime.now());
        identityMapper.updateById(identity);
    }

    private static String normalizeEmail(String email) {
        if (!StringUtils.hasText(email) || !email.contains("@")) throw new BadRequestException("邮箱格式不正确");
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static UnauthorizedException invalidCredentials() {
        return new UnauthorizedException("邮箱或密码不正确");
    }

    private MemberDto toMember(MemberEntity member) {
        return new MemberDto(member.getId(), member.getName(), member.getInitial(), member.getColor(), member.getRole());
    }

    /** 第三方身份解析的内部结果，待认领资料只进入当前服务端会话。 */
    public record ExternalResolution(AuthSessionDto session, ExternalIdentityProfile pending,
                                     List<MemberDto> candidates) {
        static ExternalResolution authenticated(AuthSessionDto session) {
            return new ExternalResolution(session, null, List.of());
        }

        static ExternalResolution pending(ExternalIdentityProfile profile, List<MemberDto> candidates) {
            return new ExternalResolution(null, profile, candidates);
        }

        public AuthResolutionDto toDto() {
            return session != null ? AuthResolutionDto.authenticated(session)
                    : AuthResolutionDto.claim(pending.provider(), pending.displayName(), candidates);
        }
    }
}
