package cn.wayuwa.wao.domain;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 文档协同在场记录：一行代表「某人此刻打开着某篇文档」。
 *
 * <p>主键用 {@code <docId>:<memberId>} 合成，心跳是一次 upsert；超过在场窗口没有心跳
 * 就视为离开（见 ADR-017）。表跨节点共享，因此两个节点上的用户能互相看见。
 */
@TableName("doc_presence")
public class DocPresenceEntity {

    @TableId
    private String id;
    private String docId;
    private String memberId;
    /** 光标所在的顶层块序号；-1 表示只在查看、没有落点。 */
    private Integer line;
    private Boolean editing;
    private LocalDateTime seenAt;

    /** 合成主键：一个人对一篇文档只有一条在场记录。 */
    public static String keyOf(String docId, String memberId) {
        return docId + ":" + memberId;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getDocId() { return docId; }
    public void setDocId(String docId) { this.docId = docId; }
    public String getMemberId() { return memberId; }
    public void setMemberId(String memberId) { this.memberId = memberId; }
    public Integer getLine() { return line; }
    public void setLine(Integer line) { this.line = line; }
    public Boolean getEditing() { return editing; }
    public void setEditing(Boolean editing) { this.editing = editing; }
    public LocalDateTime getSeenAt() { return seenAt; }
    public void setSeenAt(LocalDateTime seenAt) { this.seenAt = seenAt; }
}
