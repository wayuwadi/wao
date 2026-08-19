package cn.wayuwa.wao.service;

/** 聊天复盘 worker 的数据库租约已过期或已被其他节点接管。 */
class ChatReviewLeaseLostException extends RuntimeException {

    ChatReviewLeaseLostException() {
        super("聊天复盘执行权已转移到其他节点");
    }
}
