package cn.wayuwa.wao.dto;

import java.util.List;

/** 执行节点的安全展示信息，不包含节点令牌。 */
public record AiRunnerDto(
        String id,
        String name,
        String platform,
        List<String> engines,
        int maxConcurrency,
        int activeRuns,
        boolean online,
        String lastSeenAt,
        /** Runner 脚本是否与服务端打包的最新版一致；旧版 Runner 未上报哈希时为 null。 */
        Boolean scriptUpToDate,
        /** 后台已下发脚本更新、该电脑还没完成自更新重启。 */
        boolean updateRequested) {
}
