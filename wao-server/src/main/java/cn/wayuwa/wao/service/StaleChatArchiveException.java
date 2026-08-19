package cn.wayuwa.wao.service;

/** 较早抓取的飞书快照晚到；必须停止而不能把旧消息绑定到新版归档文档。 */
public class StaleChatArchiveException extends RuntimeException {
    public StaleChatArchiveException() {
        super("飞书聊天归档已有更新的同步结果，本次旧快照已停止；请重试整个日期范围");
    }
}
