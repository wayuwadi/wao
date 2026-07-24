# ADR-001：锁定 Nacos 2 兼容版本基线

## 状态

已采纳。

## 上下文

架构方案要求 Java 17、Spring Boot 3 与 Nacos 2.x。当前较新的 Spring Cloud Alibaba 版本已经切换到 Nacos 3.x。

## 决策

Phase 0 使用 Spring Boot 3.2.4、Spring Cloud 2023.0.1、Spring Cloud Alibaba 2023.0.1.0 与 Nacos Server 2.4.3。

## 后果

该组合保持 Nacos 2.x 约束并可完成注册发现。未来升级前必须验证 Java 17、Gateway、Nacos 客户端/服务端兼容性，并新增 ADR。
