package cn.wayuwa.wao.dto;

/**
 * 在场心跳请求。
 *
 * @param line    光标所在的顶层块序号；null / -1 表示只在查看
 * @param editing 上一个心跳周期内是否有输入
 * @param rev     客户端手上的正文版本号，服务端更新时会把最新文档一并带回
 */
public record DocPresenceRequest(Integer line, Boolean editing, Integer rev) {
}
