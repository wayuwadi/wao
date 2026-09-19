# 只读性能基准与报告对照

## 什么时候用

修改首页聚合、趋势统计或文档读取逻辑前后，用同一组命令记录服务方法耗时、SQL 次数、Mapper 返回行数和 DTO 大小。此工具连接既有 MySQL，单并发执行 SELECT；不启动应用、迁移或后台任务。普通 `mvn test` 默认跳过真实连接场景。

先运行 [本地开发体检](local-development-doctor.md)，确认已有 JDK 17、Maven 和私有连接参数可用。不要把环境文件加入 Git。

```bash
python3 deploy/scripts/performance-baseline.py --env-file .env.local-cloud
```

工具不通过 Shell 执行环境文件；仅解析允许的连接变量，已有进程环境优先。缺少必需变量时停止，不输出配置原文。工具路径不在 `PATH` 时可指定已有安装：

```bash
python3 deploy/scripts/performance-baseline.py \
  --env-file .env.local-cloud \
  --java-home /absolute/path/to/jdk-17 \
  --maven-bin /absolute/path/to/maven/bin/mvn \
  --warmups 2 --samples 5
```

默认跑五个首页场景。文档场景需要明确提供本人创建的专用验收文档 ID：

```bash
python3 deploy/scripts/performance-baseline.py \
  --env-file .env.local-cloud --doc-id 已核实的验收文档ID
```

不提供 ID 时，报告明确列出跳过的两个文档场景；不会随机挑选业务文档。文档不存在会失败，不会把空结果算成有效基准。所有 JDBC/MyBatis 异常只保留阶段、类型和代码位置，避免把 SQL 参数或正文写入报告。

## 阅读和比较

报告位于 `wao-server/target/performance/baseline-<时间戳>.json`，由 Git 忽略。报告包含 Git SHA、工作区是否有未提交改动、Java 版本、采样设置及聚合数据概况，不包含连接凭据、文档 ID、正文、任务标题或成员信息。

```bash
python3 deploy/scripts/compare-performance.py /path/to/before.json /path/to/after.json
# 机器读取
python3 deploy/scripts/compare-performance.py /path/to/before.json /path/to/after.json --json
```

对照工具从原始样本重新计算中位数，不信任摘要值；场景集合不同时只比较共有部分并列出缺项。数据概况、Java 版本和样本设置变化会提示，未提交工作区与同轮结果不稳定也会提示。

- 耗时从服务方法调用开始，到返回结束；包含本机处理和数据库往返，不含 DTO 的 JSON 序列化。
- 查询次数为 MyBatis 执行器调用数，缓存已禁用；返回行数为 Mapper 返回的行数，不是数据库扫描行数。
- `outputsStable` 使用同轮 DTO 内容摘要比较，但报告不保存摘要或正文；字节数相等并不能证明跨报告内容一致。
- 每轮使用独立的只读一致性快照。两轮行数相同，也不证明数据、网络或机器负载相同。耗时百分比仅用于定位和复测，不直接等于代码收益或线上 P95。
- 默认两轮预热、五轮采样，允许 0—5 次预热、3—20 个样本。单条 SQL 超时 10 秒，连接和网络超时有界；不要把它当压力测试工具。

## 工具自检

```bash
python3 -m unittest discover -s deploy/scripts -p 'test_compare_performance.py'
mvn -pl wao-server -Dtest=ReadOnlyPerformanceSupportTest,PerformanceBaselineReadOnlyTest test
```

未设置 `WAO_PERFORMANCE_BENCHMARK=true` 时，第二条命令只运行隔离统计/SQL 防写单测，真实数据库场景应显示跳过。写入防护有两层：MyBatis 拒绝非 SELECT，MySQL 使用 READ ONLY 事务并在结束后回滚。事务边界只服务本次基准，不替代业务接口的授权。

规格见 [只读性能基准](../specs/read-only-performance-baseline.md)，首次实测见 [验收记录](../validation/read-only-performance-baseline-2026-09-19.md)。
