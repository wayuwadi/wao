package cn.wayuwa.wao.service;

import cn.wayuwa.wao.domain.FeishuQuickCollectEventEntity;
import cn.wayuwa.wao.mapper.DocMapper;
import cn.wayuwa.wao.mapper.TaskMapper;
import cn.wayuwa.wao.mapper.FeishuQuickCollectEventMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.stereotype.Service;

/** 来源编辑只更新仍保持上次来源版本的内容；在 WAO 编辑后拒绝覆盖。 */
@Service
public class FeishuCaptureTargetGuard {
    private final DocMapper docs;
    private final TaskMapper tasks;
    private final FeishuQuickCollectEventMapper events;
    public FeishuCaptureTargetGuard(DocMapper docs, TaskMapper tasks, FeishuQuickCollectEventMapper events) {
        this.docs=docs; this.tasks=tasks; this.events=events;
    }
    /** 调用方事务内锁定来源目标，再比较上次收集版本；旧记录没有版本时保守拒绝。 */
    public boolean mayUpdate(FeishuQuickCollectEventEntity event) {
        return event != null && event.getTargetVersion() != null && event.getTargetVersion().equals(version(event));
    }
    /** 创建或来源更新成功后记录真实版本，绝不把浏览器提交的版本当事实。 */
    public void remember(FeishuQuickCollectEventEntity event) {
        if (event != null && event.getTargetId() != null) events.saveTargetVersion(event.getEventKey(), version(event));
    }
    private String version(FeishuQuickCollectEventEntity event) {
        if ("NOTE".equals(event.getCaptureKind())) {
            var doc = docs.selectByIdForUpdate(event.getTargetId());
            return doc == null || doc.getDeletedAt() != null ? null : "note:" + doc.getRev();
        }
        var task = tasks.lockById(event.getTargetId());
        if (task == null) return null;
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((task.getTitle() + "\n" + task.getDescription()).getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ex) { throw new IllegalStateException("无法核对来源版本"); }
    }
}
