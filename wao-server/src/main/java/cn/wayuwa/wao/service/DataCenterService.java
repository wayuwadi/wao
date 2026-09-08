package cn.wayuwa.wao.service;

import cn.wayuwa.wao.dto.DataCenterOverviewDto;
import cn.wayuwa.wao.dto.DataCenterSourceDto;
import cn.wayuwa.wao.mapper.DataCenterMapper;
import cn.wayuwa.wao.mapper.DataCenterSnapshotRow;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** 汇总 WAO 已有持久化数据的目录、覆盖范围和真实写入状态。 */
@Service
public class DataCenterService {

    private static final String FULL = "FULL_RECORDS";
    private static final String REGISTRY_ONLY = "REGISTRY_ONLY";
    private static final List<Definition> DEFINITIONS = List.of(
            new Definition("projects", "项目", "协作", "项目目标、状态与归属的主数据。", "项", FULL,
                    "历史项目模型没有可靠更新时间，数据中心只报告真实数量。"),
            new Definition("tasks", "任务", "协作", "任务创建、完成与项目归属记录。", "条", FULL,
                    "目录不返回任务标题、描述或分享内容。"),
            new Definition("ai-runs", "AI Run", "AI 协作", "AI 开发运行、结果与用量记录。", "次", FULL,
                    "目录不返回运行提示词、日志或结果正文。"),
            new Definition("knowledge-documents", "知识文档", "内容", "当前未删除的知识库文档。", "篇", FULL,
                    "目录不返回文档标题、正文或附件。"),
            new Definition("memory-revisions", "记忆版本", "内容", "统一记忆中心保存的不可变版本。", "版", FULL,
                    "目录不返回记忆内容、来源摘录或授权信息。"),
            new Definition("shared-chat-messages", "共享聊天", "沟通", "WAO 与飞书共享聊天的已落库消息。", "条", FULL,
                    "目录不返回消息正文、发送人或表情回执。"),
            new Definition("http-request-metrics", "请求指标", "系统", "浏览器上报的 HTTP 耗时与状态指标。", "条", FULL,
                    "目录不返回成员、接口路径或单次请求明细。"),
            new Definition("product-updates", "产品更新", "开发交付", "成功运行从 Git 提交提取的产品更新。", "条", FULL,
                    "只保存提交 SHA、scope、标题和时间；不读取作者、diff、分支历史或仓库凭据。"),
            new Definition("git-repositories", "项目 Git 源", "外部来源", "项目 AI 工作流登记的仓库地址。", "个", REGISTRY_ONLY,
                    "仅登记仓库地址与分支；不镜像仓库、作者、diff 或完整提交历史。"));

    private final DataCenterMapper mapper;

    public DataCenterService(DataCenterMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 读取当前数据目录。聚合查询不复制正文，也不会因某类空数据补演示值。
     *
     * @return 按稳定定义排序的数据中心概览
     */
    public DataCenterOverviewDto overview() {
        Map<String, DataCenterSnapshotRow> snapshots = mapper.snapshots().stream()
                .filter(row -> row.getSourceId() != null)
                .collect(Collectors.toMap(DataCenterSnapshotRow::getSourceId, Function.identity(), (left, right) -> left));
        List<DataCenterSourceDto> sources = DEFINITIONS.stream()
                .map(definition -> source(definition, snapshots.get(definition.id())))
                .toList();
        long totalRecords = sources.stream().mapToLong(DataCenterSourceDto::recordCount).sum();
        int populated = (int) sources.stream().filter(source -> source.recordCount() > 0).count();
        return new DataCenterOverviewDto(LocalDateTime.now(), sources.size(), populated, totalRecords, sources);
    }

    private DataCenterSourceDto source(Definition definition, DataCenterSnapshotRow snapshot) {
        long count = snapshot == null || snapshot.getRecordCount() == null ? 0 : snapshot.getRecordCount();
        LocalDateTime latestAt = snapshot == null ? null : snapshot.getLatestAt();
        String status = count == 0 ? "EMPTY"
                : REGISTRY_ONLY.equals(definition.coverage()) ? "REGISTERED_ONLY"
                : latestAt == null ? "TIME_UNKNOWN" : "READY";
        return new DataCenterSourceDto(definition.id(), definition.label(), definition.category(),
                definition.description(), definition.recordUnit(), count, latestAt, status,
                definition.coverage(), definition.boundary());
    }

    private record Definition(String id, String label, String category, String description,
            String recordUnit, String coverage, String boundary) {
    }
}
