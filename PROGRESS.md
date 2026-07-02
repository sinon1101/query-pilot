# 项目进度

> 每完成一项就更新此文件。新会话先读 `CLAUDE.md` 再读这里。

## 已完成

- [x] 2026-07-02 技术选型定稿（见 CLAUDE.md），Docker 环境清理，本地 git 仓库初始化

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

## 遗留事项 / 备忘

- gh CLI 已安装但需要用户执行 `gh auth login` 完成 GitHub 授权（若未完成）
- GitHub 远程仓库待创建并推送
