# DataAgent — 智能数据分析 Agent

个人简历项目：用户用自然语言提问（如"上个月哪个品类销售额最高？"），Agent 自动生成 SQL、执行查询、出错自我修正，最后生成 ECharts 图表和分析结论。

**当前进度和待办看 `PROGRESS.md`，每完成一个阶段性工作必须同步更新它。**

## 技术栈（已定，勿随意更换）

- Java 21 + Maven 3.9 + Spring Boot 3.5.x
- LLM：Spring AI **OpenAI 客户端**接阿里云百炼「**OpenAI 兼容端点**」（`spring.ai.openai.base-url=…/compatible-mode`）。
  对话用**多模态 qwen3.7-plus**（视觉 + tool call），向量用 text-embedding-v4（均走兼容端点）。
  历史：原用 spring-ai-alibaba 原生 starter + qwen-plus，因 qwen-plus 额度耗尽、且 qwen3.7-plus 仅兼容端点可用而切换
- API Key：读环境变量，不写死、不进仓库（用户本机已配置）
- MySQL 8（Docker，宿主机端口 **3307**）：业务样例数据（电商：订单/商品/用户）+ 对话历史 + Agent 执行轨迹（JPA）
- Redis Stack（Docker，宿主机端口 **6380**）：schema/业务术语向量检索（Spring AI `RedisVectorStore`）
  - 端口特意错开 3306/6379，因为本机还有另一个项目（aics）的容器占用默认端口
- 前端：静态页面（原生 JS + ECharts + SSE），放 `src/main/resources/static/`，不引入前端工程
- 部署：`docker-compose.dev.yml`（仅 MySQL+Redis，日常开发时应用在 IDEA 里跑）；`docker-compose.yml`（全量三容器，一键部署）；应用镜像多阶段构建

## 核心设计原则

1. **ReAct Agent 循环必须手写**（消息管理、tool call 解析分发、轮数控制、错误自愈、收敛前语义反思），不用框架的自动工具执行——这是本项目的面试价值核心。模型调用/向量化/向量检索等"水电煤"才用框架。
2. 三个工具：`schema_search`（**schema linking**：多粒度文档混合检索[稠密+词法 RRF] + 实体链接，非简单 RAG）、`execute_sql`（只读校验 + 超时 10s + 最大 200 行，报错原文返回给模型触发自愈，最多重试 3 轮）、`render_chart`（返回 ECharts option JSON）。
3. **反思 / Critic**（`agent/Critic`）：收敛前独立 LLM 审校语义口径，抓 SQL 跑得通但口径错的静默错误，判 revise 打回重做（fail-open）。这是"语法自愈"抓不到的一层。
4. 每步推理和工具调用落库（trace），支持执行轨迹回放。
5. 安全意识是卖点：SQL 只读沙箱、防注入校验、密钥不进仓库。
6. **业务库为 19 表准真实电商库**（刻意含脏：一词多义 `status`、命名不一致的时间字段、遗留拼音表），schema 定义的单一数据源是 `rag/SchemaKnowledge`，改表需同步它与 `docker/mysql/init/*.sql`。

## 项目结构

```
src/main/java/com/yang/dataagent/
├── agent/    # ReAct 循环核心：AgentExecutor、消息管理、轮数控制、Critic 反思
├── tool/     # 三个工具的定义与实现
├── rag/      # SchemaKnowledge catalog + 多粒度文档 + HybridSchemaRetriever（稠密+词法 RRF）
├── memory/   # 对话历史（窗口截断/摘要压缩）
├── trace/    # 执行轨迹落库 + 查询接口
├── web/      # ChatController (SSE)、静态页面
└── config/   # DashScope、数据源、样例数据初始化
```

## 约定

- 和用户交流用中文；代码标识符用英文，注释可用中文
- 提交信息用中文简述，一次提交对应一个完整的小功能
- 本项目最终目标是写进简历：做技术决策时优先考虑"面试能不能讲出深度"，其次才是省事
