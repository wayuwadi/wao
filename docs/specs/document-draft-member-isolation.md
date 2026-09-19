# 文档兼容草稿按成员隔离

localStorage 与 IndexedDB 均按成员隔离。切换账号清空当前内存编辑和排队保存，迟到读写回执不能修改新账号状态。无成员标识的旧 localStorage 草稿保留原处，不自动迁移或提交；现有按成员隔离的 IndexedDB 草稿正常恢复。
