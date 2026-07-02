# 项目进度

> 每完成一项就更新此文件。新会话先读 `CLAUDE.md` 再读这里。

## 已完成

- [x] 2026-07-02 技术选型定稿（见 CLAUDE.md），Docker 环境清理，本地 git 仓库初始化
- [x] 2026-07-02 GitHub 远程仓库建立并推送：https://github.com/sinon1101/data-analysis-agent （**当前私有**，项目成型后切换公开）

## 第一阶段：最小闭环（一句话 → SQL → 查询结果）✅ 2026-07-02 完成

- [x] Maven 骨架：Spring Boot 3.5.16 + Spring AI Alibaba 1.1.2.3（基于 Spring AI 1.1.2）+ JPA + MySQL 驱动
  - 注意：1.1.x 的 spring-ai-alibaba-bom 不再管理 dashscope starter，版本需直接指定
- [x] docker-compose.dev.yml（MySQL 8.0 @3307 + redis-stack @6380，复用本机已有镜像）+ 电商样例数据
  - biz 库 4 张表（users/products/orders/order_items），300 用户 / 3000 订单 / 6000+ 明细
  - 数据用固定种子 RAND(n) 生成、日期相对 NOW()，可复现且"上个月"类问题永远有数据
  - 坑：init 脚本必须 `SET NAMES utf8mb4`，否则容器内 mysql client 按 latin1 执行，中文双重编码入库
- [x] 接通百炼 qwen-plus + 冒烟测试（DashScopeSmokeTest，无 DASHSCOPE_API_KEY 时自动跳过）
- [x] 手写 ReAct AgentExecutor：internalToolExecutionEnabled(false) 拿到原始 tool call 手动分发，
  三重终止条件（模型收敛 / 最大 10 轮 / SQL 连续失败 3 次），每步记入 AgentStep 轨迹随响应返回
- [x] execute_sql 工具三层防线：SqlGuard 校验（仅 SELECT/WITH、黑名单关键字、禁多语句注释，25 个单测）
  + agent_ro 只读账号（数据库层仅 biz 库 SELECT 权限，已验证 UPDATE 被拒）+ 10s 超时 / 200 行截断
- [x] POST /api/chat 端到端验证通过：
  - "上个月哪个品类销售额最高" → 1 轮出正确 SQL → 手机数码 489,135.68 元（与手工查库一致）
  - 三表 join（城市消费 Top3）✓；"把商品价格改成 1 元"被拒绝 ✓

## 第二阶段：RAG 与记忆 ✅ 2026-07-03 完成

- [x] schema_search 工具：8 个文档（4 表 DDL + 4 条业务口径）向量化入 Redis（text-embedding-v4），
  按问题 topK=4 检索；系统提示词不再内联全量表结构
  - RedisVectorStore 手动装配（不用 starter 自动配置），文档 id 固定、启动全量重灌（幂等）
  - 坑：Spring AI 1.1.2 的 redis 核心模块仍叫 `spring-ai-redis-store`（不是 spring-ai-vector-store-redis）
- [x] 对话历史持久化（conversation/chat_message 表）+ 窗口截断（最近 20 条注入），
  只存 user 问题与 assistant 最终回答，工具调用归 trace；摘要压缩留作后续优化
  - 坑：Hibernate 6 对 `@Lob + NOT NULL` 的 String 在 MySQL 生成 tinytext（255 字节），
    中文回答必超长，所有长文本列改显式 `columnDefinition = "TEXT"/"MEDIUMTEXT"`
- [x] 执行轨迹落库（agent_trace/agent_trace_step 表）+ 回放接口：
  GET /api/traces?conversationId=xx（列表）、GET /api/traces/{id}（含全部步骤）
- [x] 端到端验证（同一对话两轮）：
  - "上个月哪个品类销售额最高" → schema_search → execute_sql → 手机数码 489,135.68 ✓
  - 追问"那第二名呢" → 正确理解指代 → 家用电器 295,621.61（与手工查库一致）✓
  - 期间抓到并修复一个真实 bug：模型手搓月初/月末边界算错时间窗口（只统计了 1 天），
    已在系统提示词固化月份类相对时间必须用 DATE_FORMAT 写法；错误自愈也实测触发
    （模型先用了不存在的 o.created_at，读报错后自行改为 order_time）

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

- 本机百炼 Key 存在用户环境变量 `API-KEY`（非标准名），application.yml 已做回退兼容
  `${DASHSCOPE_API_KEY:${API-KEY:}}`；建议手动补一个标准名变量：
  `setx DASHSCOPE_API_KEY %API-KEY%`（新终端生效）
- 项目成型后把 GitHub 仓库从私有切换为公开（简历需要）
- gh CLI 安装在 `C:\Program Files\GitHub CLI\gh.exe`，新终端若提示找不到 gh 是 PATH 未刷新
