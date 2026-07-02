# 项目进度

> 每完成一项就更新此文件。新会话先读 `CLAUDE.md` 再读这里。

## 已完成

- [x] 2026-07-02 技术选型定稿（见 CLAUDE.md），Docker 环境清理，本地 git 仓库初始化
- [x] 2026-07-02 GitHub 远程仓库建立并推送：https://github.com/sinon1101/data-analysis-agent （**当前私有**，项目成型后切换公开）

## 第一阶段：最小闭环（一句话 → SQL → 查询结果）

- [ ] Maven 骨架：pom.xml（Spring Boot 3.5 + Spring AI Alibaba + JPA + MySQL 驱动）
- [ ] docker-compose.dev.yml（MySQL 3307 + Redis Stack 6380）+ 电商样例数据初始化 SQL
- [ ] 接通百炼 qwen-plus（环境变量注入 API Key），写一个连通性冒烟测试
- [ ] 手写 ReAct AgentExecutor：消息列表维护、tool call 解析分发、最大轮数控制
- [ ] execute_sql 工具：只读校验、超时、行数限制、错误信息回传自愈（最多 3 轮）
- [ ] 简单 REST 接口跑通端到端：POST 问题 → 返回 SQL + 查询结果

## 第二阶段：RAG 与记忆

- [ ] schema_search 工具：表 DDL + 业务术语向量化入 Redis，按问题检索注入
- [ ] 对话历史持久化（MySQL）+ 上下文窗口管理
- [ ] Agent 执行轨迹（trace）落库

## 第三阶段：呈现与部署

- [ ] SSE 流式输出
- [ ] render_chart 工具 + 前端页面（聊天框 + ECharts + 执行轨迹面板）
- [ ] 全量 docker-compose.yml + 应用镜像多阶段构建，一键部署验证
- [ ] README（架构图、演示 GIF、快速开始）
- [ ] 小评测集：统计 SQL 一次成功率 / 自愈后成功率（简历数据来源）

## 第四阶段（可选）：Text-to-SQL 小模型微调实验

> 前三阶段全部完成且有余力时再启动。本机 GPU 仅 4GB 显存（RTX 3050 Ti Laptop），**不能本地训练**，走云端。

- [ ] 用 qwen-max 蒸馏构造 Text-to-SQL 训练数据（基于本项目库表场景）
- [ ] 云端 LoRA 微调 Qwen 小模型：租 AutoDL GPU（4090 约 2 元/时）或用百炼托管微调
- [ ] 用第三阶段的自建评测集对比：微调小模型 vs qwen-plus 的 SQL 准确率 / 成本 / 延迟
- [ ] 简历表述目标："构造 X 条训练数据微调小模型，评测集准确率 A%→B%，推理成本降低 C 倍"

## 遗留事项 / 备忘

- 项目成型后把 GitHub 仓库从私有切换为公开（简历需要）
- gh CLI 安装在 `C:\Program Files\GitHub CLI\gh.exe`，新终端若提示找不到 gh 是 PATH 未刷新
