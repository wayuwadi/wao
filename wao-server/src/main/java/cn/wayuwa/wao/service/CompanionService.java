package cn.wayuwa.wao.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.ConflictException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.domain.AiTaskRunEntity;
import cn.wayuwa.wao.domain.CompanionActivityReceiptEntity;
import cn.wayuwa.wao.domain.CompanionDeviceEntity;
import cn.wayuwa.wao.domain.CompanionPairingEntity;
import cn.wayuwa.wao.domain.ProjectEntity;
import cn.wayuwa.wao.domain.TaskEntity;
import cn.wayuwa.wao.dto.CompanionActivityDto;
import cn.wayuwa.wao.dto.CompanionActivitySnapshotDto;
import cn.wayuwa.wao.dto.CompanionDeviceDto;
import cn.wayuwa.wao.dto.CompanionPairingCreatedDto;
import cn.wayuwa.wao.dto.CompanionPairingPollDto;
import cn.wayuwa.wao.dto.CompanionPairingPreviewDto;
import cn.wayuwa.wao.dto.CreateCompanionPairingRequest;
import cn.wayuwa.wao.mapper.AiTaskRunMapper;
import cn.wayuwa.wao.mapper.CompanionActivityReceiptMapper;
import cn.wayuwa.wao.mapper.CompanionDeviceMapper;
import cn.wayuwa.wao.mapper.CompanionPairingMapper;
import cn.wayuwa.wao.mapper.ProjectMapper;
import cn.wayuwa.wao.mapper.TaskMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriUtils;

/** 桌宠网页配对、最小权限设备认证和 AI Run 活动聚合。 */
@Service
public class CompanionService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final List<String> ACTIVE = List.of("queued", "claimed", "running");
    private static final List<String> FINISHED = List.of("succeeded", "failed");

    private final CompanionPairingMapper pairings;
    private final CompanionDeviceMapper devices;
    private final CompanionActivityReceiptMapper receipts;
    private final AiTaskRunMapper runs;
    private final TaskMapper tasks;
    private final ProjectMapper projects;

    public CompanionService(CompanionPairingMapper pairings, CompanionDeviceMapper devices,
            CompanionActivityReceiptMapper receipts, AiTaskRunMapper runs, TaskMapper tasks,
            ProjectMapper projects) {
        this.pairings = pairings;
        this.devices = devices;
        this.receipts = receipts;
        this.runs = runs;
        this.tasks = tasks;
        this.projects = projects;
    }

    /** 创建十分钟配对请求；高熵明文只返回给发起设备。 */
    public CompanionPairingCreatedDto createPairing(CreateCompanionPairingRequest request, String webBaseUrl) {
        LocalDateTime now = LocalDateTime.now();
        String rawCode = userCode();
        String pollSecret = secret();
        CompanionPairingEntity pairing = new CompanionPairingEntity();
        pairing.setId(id("cp"));
        pairing.setCodeHash(hash(normalizeCode(rawCode)));
        pairing.setPollSecretHash(hash(pollSecret));
        pairing.setClientDeviceId(request.deviceId().trim());
        pairing.setDeviceName(request.deviceName().trim());
        pairing.setPlatform(request.platform().trim());
        pairing.setStatus("PENDING");
        pairing.setCreatedAt(now);
        pairing.setExpiresAt(now.plusMinutes(10));
        pairings.insert(pairing);
        String verificationUrl = webBaseUrl + "/settings?tab=companion&code="
                + UriUtils.encodeQueryParam(rawCode, StandardCharsets.UTF_8);
        return new CompanionPairingCreatedDto(pairing.getId(), rawCode, pollSecret,
                verificationUrl, pairing.getExpiresAt().toString());
    }

    /** 供已登录网页预览短码对应的设备，不泄露任何 secret。 */
    public CompanionPairingPreviewDto preview(String code) {
        String normalized = normalizeCode(code);
        CompanionPairingEntity pairing = pairings.selectOne(new LambdaQueryWrapper<CompanionPairingEntity>()
                .eq(CompanionPairingEntity::getCodeHash, hash(normalized)));
        requireUsable(pairing);
        return previewDto(pairing, displayCode(normalized));
    }

    /** 将短码绑定到当前 WAO 账号和成员；同一码只能批准一次。 */
    @Transactional
    public CompanionPairingPreviewDto approve(String code, String accountId, String memberId) {
        if (!StringUtils.hasText(accountId) || !StringUtils.hasText(memberId)) {
            throw new UnauthorizedException("登录已过期，请重新登录");
        }
        String normalized = normalizeCode(code);
        CompanionPairingEntity pairing = pairings.selectByCodeForUpdate(hash(normalized));
        requireUsable(pairing);
        if (!"PENDING".equals(pairing.getStatus())) {
            throw new ConflictException("该设备已经批准，请回到桌宠完成连接");
        }
        LocalDateTime now = LocalDateTime.now();
        pairing.setAccountId(accountId);
        pairing.setMemberId(memberId);
        pairing.setStatus("APPROVED");
        pairing.setApprovedAt(now);
        pairings.updateById(pairing);
        return previewDto(pairing, displayCode(normalized));
    }

    /** 设备轮询批准结果；批准后仅在首次成功领取时返回一次设备令牌。 */
    @Transactional
    public CompanionPairingPollDto poll(String pairingId, String pollSecret) {
        CompanionPairingEntity pairing = pairings.selectByIdForUpdate(pairingId);
        if (pairing == null || !sameHash(pairing.getPollSecretHash(), pollSecret)) {
            throw new UnauthorizedException("配对请求不存在或轮询密钥不正确");
        }
        LocalDateTime now = LocalDateTime.now();
        if (pairing.getExpiresAt().isBefore(now)) {
            if (!"PAIRED".equals(pairing.getStatus())) {
                pairing.setStatus("EXPIRED");
                pairings.updateById(pairing);
            }
            return new CompanionPairingPollDto("EXPIRED", null, null, pairing.getExpiresAt().toString());
        }
        if ("PENDING".equals(pairing.getStatus())) {
            return new CompanionPairingPollDto("PENDING", null, null, pairing.getExpiresAt().toString());
        }
        CompanionDeviceEntity device = devices.selectOne(new LambdaQueryWrapper<CompanionDeviceEntity>()
                .eq(CompanionDeviceEntity::getClientDeviceId, pairing.getClientDeviceId()));
        if ("APPROVED".equals(pairing.getStatus())) {
            if (device != null && !device.getAccountId().equals(pairing.getAccountId())) {
                throw new ConflictException("该设备标识已经属于另一个 WAO 账号，请在客户端重新生成设备身份");
            }
            boolean create = device == null;
            if (create) {
                device = new CompanionDeviceEntity();
                device.setId(id("cd"));
                device.setClientDeviceId(pairing.getClientDeviceId());
            }
            device.setAccountId(pairing.getAccountId());
            device.setMemberId(pairing.getMemberId());
            device.setName(pairing.getDeviceName());
            device.setPlatform(pairing.getPlatform());
            String deviceToken = secret();
            device.setTokenHash(hash(deviceToken));
            device.setCreatedAt(now);
            device.setLastSeenAt(now);
            device.setReadBeforeAt(now);
            device.setRevokedAt(null);
            if (create) devices.insert(device); else devices.updateById(device);
            pairing.setStatus("PAIRED");
            pairing.setConsumedAt(now);
            pairings.updateById(pairing);
            return new CompanionPairingPollDto("PAIRED", device.getId(), deviceToken,
                    pairing.getExpiresAt().toString());
        }
        return new CompanionPairingPollDto("PAIRED", device == null ? null : device.getId(), null,
                pairing.getExpiresAt().toString());
    }

    /** 当前账号已授权且未撤销的桌宠设备。 */
    public List<CompanionDeviceDto> listDevices(String accountId) {
        return devices.selectList(new LambdaQueryWrapper<CompanionDeviceEntity>()
                        .eq(CompanionDeviceEntity::getAccountId, accountId)
                        .isNull(CompanionDeviceEntity::getRevokedAt)
                        .orderByDesc(CompanionDeviceEntity::getCreatedAt))
                .stream().map(this::deviceDto).toList();
    }

    /** 撤销当前账号的一台设备；其他账号不能按 id 越权撤销。 */
    public void revokeDevice(String deviceId, String accountId) {
        CompanionDeviceEntity device = devices.selectById(deviceId);
        if (device == null || !device.getAccountId().equals(accountId) || device.getRevokedAt() != null) {
            throw new NotFoundException("桌宠设备不存在或已经撤销");
        }
        device.setRevokedAt(LocalDateTime.now());
        devices.updateById(device);
    }

    /** 使用设备令牌读取当前成员自己的桌宠活动。 */
    public CompanionActivitySnapshotDto activities(String authorization, String webBaseUrl) {
        CompanionDeviceEntity device = authenticate(authorization);
        LocalDateTime now = LocalDateTime.now();
        List<AiTaskRunEntity> selected = new ArrayList<>();
        selected.addAll(runs.selectList(new LambdaQueryWrapper<AiTaskRunEntity>()
                .eq(AiTaskRunEntity::getRequestedBy, device.getMemberId())
                .in(AiTaskRunEntity::getStatus, ACTIVE)
                .orderByDesc(AiTaskRunEntity::getCreatedAt)));
        selected.addAll(runs.selectList(new LambdaQueryWrapper<AiTaskRunEntity>()
                .eq(AiTaskRunEntity::getRequestedBy, device.getMemberId())
                .in(AiTaskRunEntity::getStatus, FINISHED)
                .ge(AiTaskRunEntity::getFinishedAt, now.minusDays(7))
                .orderByDesc(AiTaskRunEntity::getFinishedAt)
                .last("LIMIT 20")));

        Map<String, TaskEntity> taskById = batchMap(selected.stream().map(AiTaskRunEntity::getTaskId).toList(),
                tasks::selectBatchIds, TaskEntity::getId);
        Map<String, ProjectEntity> projectById = batchMap(selected.stream().map(AiTaskRunEntity::getProjectId).toList(),
                projects::selectBatchIds, ProjectEntity::getId);
        Set<String> readIds = receipts.selectList(new LambdaQueryWrapper<CompanionActivityReceiptEntity>()
                        .eq(CompanionActivityReceiptEntity::getDeviceId, device.getId()))
                .stream().map(CompanionActivityReceiptEntity::getActivityId).collect(Collectors.toSet());

        List<CompanionActivityDto> items = selected.stream().map(run -> {
            LocalDateTime occurred = run.getFinishedAt() == null ? run.getCreatedAt() : run.getFinishedAt();
            boolean finished = FINISHED.contains(run.getStatus());
            boolean unread = finished && occurred != null && !occurred.isBefore(device.getCreatedAt())
                    && (device.getReadBeforeAt() == null || occurred.isAfter(device.getReadBeforeAt()))
                    && !readIds.contains(run.getId());
            TaskEntity task = taskById.get(run.getTaskId());
            ProjectEntity project = projectById.get(run.getProjectId());
            return new CompanionActivityDto(run.getId(), activityStatus(run),
                    task == null ? run.getTaskId() : task.getTitle(),
                    project == null ? run.getProjectId() : project.getName(),
                    summary(run), occurred == null ? null : occurred.toString(),
                    deepLink(webBaseUrl, run), unread);
        }).sorted(Comparator.comparingInt((CompanionActivityDto item) -> priority(item.status()))
                .thenComparing(CompanionActivityDto::occurredAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();

        int unread = (int) items.stream().filter(CompanionActivityDto::unread).count();
        return new CompanionActivitySnapshotDto(aggregate(items), unread, now.toString(), items);
    }

    /** 确认当前设备的一条已结束活动；进行中的活动点击后只打开页面，不产生假已读。 */
    public void readActivity(String authorization, String activityId) {
        CompanionDeviceEntity device = authenticate(authorization);
        AiTaskRunEntity run = runs.selectById(activityId);
        if (run == null || !device.getMemberId().equals(run.getRequestedBy())) {
            throw new NotFoundException("桌宠活动不存在");
        }
        if (!FINISHED.contains(run.getStatus())) return;
        CompanionActivityReceiptEntity existing = receipts.selectOne(
                new LambdaQueryWrapper<CompanionActivityReceiptEntity>()
                        .eq(CompanionActivityReceiptEntity::getDeviceId, device.getId())
                        .eq(CompanionActivityReceiptEntity::getActivityId, activityId));
        LocalDateTime now = LocalDateTime.now();
        if (existing == null) {
            existing = new CompanionActivityReceiptEntity();
            existing.setId(id("cr"));
            existing.setDeviceId(device.getId());
            existing.setActivityId(activityId);
            existing.setReadAt(now);
            receipts.insert(existing);
        } else {
            existing.setReadAt(now);
            receipts.updateById(existing);
        }
    }

    /** 将当前时刻之前已经结束的活动批量设为已读。 */
    public void readAll(String authorization) {
        CompanionDeviceEntity device = authenticate(authorization);
        device.setReadBeforeAt(LocalDateTime.now());
        devices.updateById(device);
    }

    private CompanionDeviceEntity authenticate(String authorization) {
        if (!StringUtils.hasText(authorization) || !authorization.startsWith("Bearer ")) {
            throw new UnauthorizedException("缺少桌宠设备令牌");
        }
        String token = authorization.substring(7).trim();
        if (token.length() < 32) throw new UnauthorizedException("桌宠设备令牌无效");
        CompanionDeviceEntity device = devices.selectOne(new LambdaQueryWrapper<CompanionDeviceEntity>()
                .eq(CompanionDeviceEntity::getTokenHash, hash(token))
                .isNull(CompanionDeviceEntity::getRevokedAt));
        if (device == null) throw new UnauthorizedException("桌宠设备已撤销或令牌无效");
        LocalDateTime now = LocalDateTime.now();
        if (device.getLastSeenAt() == null || device.getLastSeenAt().isBefore(now.minusMinutes(1))) {
            device.setLastSeenAt(now);
            devices.updateById(device);
        }
        return device;
    }

    private void requireUsable(CompanionPairingEntity pairing) {
        if (pairing == null || "EXPIRED".equals(pairing.getStatus())
                || pairing.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new NotFoundException("配对码不存在或已经过期");
        }
    }

    private CompanionPairingPreviewDto previewDto(CompanionPairingEntity pairing, String code) {
        return new CompanionPairingPreviewDto(code, pairing.getDeviceName(), pairing.getPlatform(),
                pairing.getStatus(), pairing.getExpiresAt().toString());
    }

    private CompanionDeviceDto deviceDto(CompanionDeviceEntity device) {
        return new CompanionDeviceDto(device.getId(), device.getName(), device.getPlatform(),
                device.getCreatedAt().toString(),
                device.getLastSeenAt() == null ? null : device.getLastSeenAt().toString());
    }

    private String deepLink(String baseUrl, AiTaskRunEntity run) {
        return baseUrl + "/projects/" + UriUtils.encodePathSegment(run.getProjectId(), StandardCharsets.UTF_8)
                + "?task=" + UriUtils.encodeQueryParam(run.getTaskId(), StandardCharsets.UTF_8);
    }

    private String activityStatus(AiTaskRunEntity run) {
        if (ACTIVE.contains(run.getStatus())) return "RUNNING";
        if ("succeeded".equals(run.getStatus())) return "READY";
        if ("failed".equals(run.getStatus()) && Integer.valueOf(3).equals(run.getExitCode())) {
            return "NEEDS_INPUT";
        }
        return "BLOCKED";
    }

    private String aggregate(List<CompanionActivityDto> items) {
        for (String status : List.of("NEEDS_INPUT", "BLOCKED", "READY")) {
            if (items.stream().anyMatch(item -> item.unread() && status.equals(item.status()))) return status;
        }
        return items.stream().anyMatch(item -> "RUNNING".equals(item.status())) ? "RUNNING" : "IDLE";
    }

    private int priority(String status) {
        return switch (status) {
            case "NEEDS_INPUT" -> 0;
            case "BLOCKED" -> 1;
            case "READY" -> 2;
            case "RUNNING" -> 3;
            default -> 4;
        };
    }

    private String summary(AiTaskRunEntity run) {
        String text = StringUtils.hasText(run.getLatestProgress()) ? run.getLatestProgress() : run.getResultSummary();
        if (!StringUtils.hasText(text)) {
            return ACTIVE.contains(run.getStatus()) ? "AI 正在处理" : "打开 WAO 查看运行详情";
        }
        String compact = text.replaceAll("\\s+", " ").trim();
        return compact.length() <= 160 ? compact : compact.substring(0, 157) + "...";
    }

    private static <T> Map<String, T> batchMap(List<String> rawIds,
            Function<List<String>, List<T>> loader, Function<T, String> idExtractor) {
        List<String> ids = rawIds.stream().filter(StringUtils::hasText).distinct().toList();
        if (ids.isEmpty()) return Map.of();
        return loader.apply(ids).stream().collect(Collectors.toMap(idExtractor, Function.identity()));
    }

    private String userCode() {
        StringBuilder value = new StringBuilder(8);
        for (int i = 0; i < 8; i++) value.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
        return displayCode(value.toString());
    }

    private String secret() {
        byte[] bytes = new byte[36];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String normalizeCode(String code) {
        if (!StringUtils.hasText(code)) throw new BadRequestException("请输入配对码");
        String normalized = code.replace("-", "").replace(" ", "").trim().toUpperCase(Locale.ROOT);
        if (normalized.length() != 8 || normalized.chars().anyMatch(ch -> CODE_ALPHABET.indexOf(ch) < 0)) {
            throw new BadRequestException("配对码格式不正确");
        }
        return normalized;
    }

    private String displayCode(String normalized) {
        return normalized.substring(0, 4) + "-" + normalized.substring(4);
    }

    private boolean sameHash(String expectedHash, String rawValue) {
        if (!StringUtils.hasText(rawValue)) return false;
        return MessageDigest.isEqual(expectedHash.getBytes(StandardCharsets.US_ASCII),
                hash(rawValue).getBytes(StandardCharsets.US_ASCII));
    }

    private String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String id(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 30);
    }
}
