package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.domain.AiTaskRunEntity;
import cn.wayuwa.wao.domain.CompanionActivityReceiptEntity;
import cn.wayuwa.wao.domain.CompanionDeviceEntity;
import cn.wayuwa.wao.domain.CompanionPairingEntity;
import cn.wayuwa.wao.domain.ProjectEntity;
import cn.wayuwa.wao.domain.TaskEntity;
import cn.wayuwa.wao.dto.CreateCompanionPairingRequest;
import cn.wayuwa.wao.mapper.AiTaskRunMapper;
import cn.wayuwa.wao.mapper.CompanionActivityReceiptMapper;
import cn.wayuwa.wao.mapper.CompanionDeviceMapper;
import cn.wayuwa.wao.mapper.CompanionPairingMapper;
import cn.wayuwa.wao.mapper.ProjectMapper;
import cn.wayuwa.wao.mapper.TaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CompanionServiceTest {
    private CompanionPairingMapper pairings;
    private CompanionDeviceMapper devices;
    private CompanionActivityReceiptMapper receipts;
    private AiTaskRunMapper runs;
    private TaskMapper tasks;
    private ProjectMapper projects;
    private CompanionService service;

    @BeforeEach
    void setUp() {
        pairings = mock(CompanionPairingMapper.class);
        devices = mock(CompanionDeviceMapper.class);
        receipts = mock(CompanionActivityReceiptMapper.class);
        runs = mock(AiTaskRunMapper.class);
        tasks = mock(TaskMapper.class);
        projects = mock(ProjectMapper.class);
        service = new CompanionService(pairings, devices, receipts, runs, tasks, projects);
    }

    @Test
    void createApproveAndPollActivatesAHashedDeviceToken() {
        var created = service.createPairing(new CreateCompanionPairingRequest(
                "client-1", "书房电脑", "windows-x64"), "https://wao.example");
        ArgumentCaptor<CompanionPairingEntity> pairingCaptor = ArgumentCaptor.forClass(CompanionPairingEntity.class);
        verify(pairings).insert(pairingCaptor.capture());
        CompanionPairingEntity pairing = pairingCaptor.getValue();

        assertThat(created.verificationUrl()).isEqualTo(
                "https://wao.example/settings?tab=companion&code=" + created.userCode());
        assertThat(pairing.getPollSecretHash()).hasSize(64).isNotEqualTo(created.pollSecret());

        when(pairings.selectByCodeForUpdate(anyString())).thenReturn(pairing);
        var approved = service.approve(created.userCode(), "account-1", "member-1");
        assertThat(approved.status()).isEqualTo("APPROVED");

        when(pairings.selectByIdForUpdate(pairing.getId())).thenReturn(pairing);
        when(devices.selectOne(any())).thenReturn(null);
        var polled = service.poll(pairing.getId(), created.pollSecret());

        ArgumentCaptor<CompanionDeviceEntity> deviceCaptor = ArgumentCaptor.forClass(CompanionDeviceEntity.class);
        verify(devices).insert(deviceCaptor.capture());
        CompanionDeviceEntity device = deviceCaptor.getValue();
        assertThat(polled.status()).isEqualTo("PAIRED");
        assertThat(polled.deviceToken()).isNotBlank();
        assertThat(device.getAccountId()).isEqualTo("account-1");
        assertThat(device.getMemberId()).isEqualTo("member-1");
        assertThat(device.getTokenHash()).hasSize(64).isNotEqualTo(polled.deviceToken());
        assertThat(device.getReadBeforeAt()).isNotNull();

        var repeated = service.poll(pairing.getId(), created.pollSecret());
        assertThat(repeated.status()).isEqualTo("PAIRED");
        assertThat(repeated.deviceToken()).isNull();
    }

    @Test
    void pollRejectsWrongSecretAndReportsExpiryWithoutCreatingDevice() {
        var created = service.createPairing(new CreateCompanionPairingRequest(
                "client-2", "公司电脑", "windows-x64"), "https://wao.example");
        ArgumentCaptor<CompanionPairingEntity> captor = ArgumentCaptor.forClass(CompanionPairingEntity.class);
        verify(pairings).insert(captor.capture());
        CompanionPairingEntity pairing = captor.getValue();
        when(pairings.selectByIdForUpdate(pairing.getId())).thenReturn(pairing);

        assertThatThrownBy(() -> service.poll(pairing.getId(), "wrong-secret-that-is-deliberately-long-enough"))
                .isInstanceOf(UnauthorizedException.class);

        pairing.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        assertThat(service.poll(pairing.getId(), created.pollSecret()).status()).isEqualTo("EXPIRED");
        verify(devices, never()).insert(any(CompanionDeviceEntity.class));
    }

    @Test
    void activitiesAreMemberScopedUnreadAndFollowCodexPriority() {
        LocalDateTime now = LocalDateTime.now();
        CompanionDeviceEntity device = device("device-1", "member-1", now.minusHours(1));
        device.setLastSeenAt(now);
        device.setReadBeforeAt(now.minusMinutes(30));
        when(devices.selectOne(any())).thenReturn(device);

        AiTaskRunEntity active = run("run-active", "running", null, now.minusMinutes(4));
        AiTaskRunEntity ready = run("run-ready", "succeeded", 0, now.minusMinutes(3));
        AiTaskRunEntity blocked = run("run-blocked", "failed", 1, now.minusMinutes(2));
        AiTaskRunEntity needsInput = run("run-input", "failed", 3, now.minusMinutes(1));
        when(runs.selectList(any())).thenReturn(List.of(active), List.of(ready, blocked, needsInput));
        when(receipts.selectList(any())).thenReturn(List.of());
        when(tasks.selectBatchIds(any())).thenReturn(List.of(task("task-1", "实现桌宠")));
        when(projects.selectBatchIds(any())).thenReturn(List.of(project("project-1", "WAO")));

        var snapshot = service.activities("Bearer a-valid-device-token-that-is-long-enough",
                "https://wao.example");

        assertThat(snapshot.status()).isEqualTo("NEEDS_INPUT");
        assertThat(snapshot.unreadCount()).isEqualTo(3);
        assertThat(snapshot.activities()).extracting(item -> item.status())
                .containsExactly("NEEDS_INPUT", "BLOCKED", "READY", "RUNNING");
        assertThat(snapshot.activities()).allMatch(item -> item.deepLink()
                .equals("https://wao.example/projects/project-1?task=task-1"));
    }

    @Test
    void receiptAndReadAllAreStoredPerAuthenticatedDevice() {
        CompanionDeviceEntity device = device("device-1", "member-1", LocalDateTime.now().minusDays(1));
        device.setLastSeenAt(LocalDateTime.now());
        when(devices.selectOne(any())).thenReturn(device);
        AiTaskRunEntity finished = run("run-ready", "succeeded", 0, LocalDateTime.now());
        when(runs.selectById("run-ready")).thenReturn(finished);
        when(receipts.selectOne(any())).thenReturn(null);

        service.readActivity("Bearer a-valid-device-token-that-is-long-enough", "run-ready");
        ArgumentCaptor<CompanionActivityReceiptEntity> receipt =
                ArgumentCaptor.forClass(CompanionActivityReceiptEntity.class);
        verify(receipts).insert(receipt.capture());
        assertThat(receipt.getValue().getDeviceId()).isEqualTo("device-1");
        assertThat(receipt.getValue().getActivityId()).isEqualTo("run-ready");

        service.readAll("Bearer a-valid-device-token-that-is-long-enough");
        assertThat(device.getReadBeforeAt()).isNotNull();
        verify(devices).updateById(device);
    }

    @Test
    void revokeCannotCrossAccountBoundary() {
        CompanionDeviceEntity device = device("device-1", "member-1", LocalDateTime.now());
        device.setAccountId("account-1");
        when(devices.selectById("device-1")).thenReturn(device);

        assertThatThrownBy(() -> service.revokeDevice("device-1", "account-2"))
                .isInstanceOf(NotFoundException.class);
        verify(devices, never()).updateById(any(CompanionDeviceEntity.class));
    }

    private CompanionDeviceEntity device(String id, String memberId, LocalDateTime createdAt) {
        CompanionDeviceEntity value = new CompanionDeviceEntity();
        value.setId(id);
        value.setClientDeviceId("client-1");
        value.setAccountId("account-1");
        value.setMemberId(memberId);
        value.setName("书房电脑");
        value.setPlatform("windows-x64");
        value.setCreatedAt(createdAt);
        return value;
    }

    private AiTaskRunEntity run(String id, String status, Integer exitCode, LocalDateTime occurredAt) {
        AiTaskRunEntity value = new AiTaskRunEntity();
        value.setId(id);
        value.setTaskId("task-1");
        value.setProjectId("project-1");
        value.setRequestedBy("member-1");
        value.setStatus(status);
        value.setExitCode(exitCode);
        value.setCreatedAt(occurredAt.minusMinutes(1));
        if (!List.of("queued", "claimed", "running").contains(status)) value.setFinishedAt(occurredAt);
        value.setLatestProgress("正在更新桌面宠物状态");
        return value;
    }

    private TaskEntity task(String id, String title) {
        TaskEntity value = new TaskEntity();
        value.setId(id);
        value.setTitle(title);
        return value;
    }

    private ProjectEntity project(String id, String name) {
        ProjectEntity value = new ProjectEntity();
        value.setId(id);
        value.setName(name);
        return value;
    }
}
