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

- [x] 2026-07-03 SSE 流式输出：AgentExecutor 每轮改走 chatModel.stream()，
  文本增量实时回调 AgentEventListener（打字机数据源），tool call 分片手写聚合
  （实测 DashScope 把 tool call 完整放在最后一个 chunk，聚合逻辑仍按 OpenAI 风格
  增量协议实现以兼容两种形状）；POST /api/chat/stream（SseEmitter + 虚拟线程），
  事件序列 meta → delta*n → tool/step*n → done，客户端断开不影响落库；
  同步 /api/chat 保留（脚本/评测用）。端到端验证：首问 + 携带 conversationId 追问均正确
- [x] 2026-07-03 render_chart 工具 + 前端页面：
  - render_chart：模型只给数据（chartType/title/categories/series），option 由服务端确定性组装
    （bar/line/pie，配色与坐标轴规范固定在代码里，色板经过 CVD 校验、固定顺序分配）；
    校验失败原文回传触发模型修正；AgentResult 新增 chartOption 随 done 事件下发
  - 出图兜底（guardrail）：qwen-plus 对"排行类问题必须出图"的提示词遵从不稳定，
    改为收敛前规则检查——结果是 2~50 行"标签+数值"且未出图时注入一次 CHART_REMINDER
    打断收敛（步骤类型 guardrail 落轨迹），模型对明细清单仍可拒绝出图；实测精确触发
  - 前端 static/：原生 JS 手写 SSE 解析（fetch+ReadableStream，EventSource 不支持 POST），
    打字机效果、执行轨迹面板（思考/工具行实时定稿）、ECharts 本地 vendor 渲染、SQL 折叠；
    无头 Edge 截图验证首页布局与 bar/pie 真实渲染
  - 顺手修复：收敛轮的最终回答不再重复记为 thought 步骤（轨迹重复 + 前端闪烁）
- [x] 2026-07-03 全量 docker-compose.yml + 应用镜像多阶段构建，一键部署验证通过：
  - Dockerfile 两阶段：maven 打包（阿里云镜像源 + pom 先拷贝做依赖层缓存）→ 21-jre 运行
  - docker-compose.yml 三容器，app 用 SPRING_PROFILES_ACTIVE=docker 切换容器内连接地址
    （application-docker.yml），DASHSCOPE_API_KEY 从宿主机透传、缺失时 compose 直接报错拒启
  - 与 dev compose 共用容器名/数据卷，开发与部署模式切换数据不丢
  - 实测 `docker compose up -d --build` 后 /api/chat 问答正确
- [x] 2026-07-04 README：亮点、mermaid 架构图、评测数据表、快速开始、真实截图（docs/img/）
  - 演示 GIF 留了 TODO 占位，需要用户自己录（建议 ScreenToGif：提问 → 流式 → 轨迹 → 图表）
- [x] 2026-07-03 小评测集（简历数据来源）：eval/questions.txt 20 题（单表聚合/多表 join/
  时间窗口/业务口径/排行趋势占比）+ run_eval.py（标准库无依赖，打 /api/chat 从轨迹自动判定）
  - **评测结果：SQL 一次成功率 90%（18/20），自愈后成功率 100%（20/20），任务完成率 100%，
    出图 11/20，平均 14.6s/题，平均 SQL 尝试 1.30 次**（明细 eval/results-20260703-143335.json）
  - 答案数值正确性不自动断言（样例数据按相对日期动态生成，硬编码期望值会脆化），人工抽查
  - 评测第一轮抓到并修复一个偶发 bug：DashScope 流式偶发丢 tool call 参数的尾部分片
    （缺结尾 "}"），损坏 JSON 回传被 InvalidParameter 400 拒绝炸掉整轮 →
    新增 ToolCallJsonRepair 括号平衡修复（修不好降级 "{}" 走工具报错自愈），7 个单测

## 第四阶段：大库化 + schema linking + 反思 ✅ 2026-07-07 完成

> 动机：4 表 demo 太小，普通 RAG 沦为摆设、语义错无从暴露。把库做大做脏，
> 让 RAG（schema linking）和反思从"装饰"变成"刚需"，且全部能被评测集量化。

- [x] biz 库从 4 表扩到 **19 表**（docker/mysql/init/01-schema.sql + 03-biz-ext-data.sql）：
  新增交易域（payment/refund/shipment，三表 status 枚举**互不相同**）、营销域（coupon/user_coupon 核销、
  promotion）、商品域（brand/category/inventory 多仓/warehouse/product_review）、用户域（会员/地址），
  外加遗留拼音表 t_order_ext（beizhu/youhui_jine/yhq_id）。时间字段故意混用
  created_at/order_time/gmt_create/pay_time/apply_time/ship_time（真实公司多团队命名不一致）。
  - 原 4 表语义**不动**，只加 products.brand_id；新数据全部 RAND(seed) 可复现、日期相对 NOW()
  - 坑：text-embedding-v4 单次 batch 上限 **10**，灌库分批必须 ≤10，否则 400 InvalidParameter
- [x] **RAG 升级为真 schema linking**（rag/ + tool/SchemaSearchTool）：
  - 单一数据源 SchemaKnowledge（catalog），生成**多粒度文档**：表摘要 + 列级（列名/类型/注释/外键）
    + 取值文档（枚举值→字段）+ 口径文档，共 **158 篇**入 Redis
  - **混合检索** HybridSchemaRetriever：稠密向量（text-embedding-v4）+ 词法（CJK 字符 bigram
    + ASCII token，IDF 加权，无需中文分词器）双路召回，**RRF 融合**（k=60，只用名次不用异构分数）
  - **确定性实体链接**：扫描问题中出现的已知枚举值（顺丰→shipment.carrier、金卡→level_name、
    核销→used），直接锁定字段；结构化输出（命中表渲染完整列 + 取值映射 + 相关口径），不再堆 DDL
- [x] **反思 / Critic 语义审校**（agent/Critic + AgentExecutor 收敛前接入）：
  独立 LLM 审"问题+SQL+结果+草稿结论"是否**语义**正确，专抓 SQL 能跑通但口径错的**静默错误**
  （分品类误用 total_amount、漏有效订单过滤、status 张冠李戴、多地址重复计数），判 revise 打回主循环
  重做，限 1 次防拉锯；fail-open（审校异常/解析失败一律放行，不误伤、不阻断）。轨迹新增 reflection 步骤类型
- [x] 评测（eval/questions-v2.txt 20 题，专打新表/脏命名/黑话/Critic 陷阱）：
  - **v2 结果：SQL 一次成功率 100%（20/20）、任务完成率 100%、出图 11/20、平均 16.9s/题、平均 SQL 1.40 次**
    （明细 eval/results-20260707-113410.json）
  - 数值人工核验：品牌销售额 Top5、白牌占比 23.1%、上月各品类动销率 100% 均与手工查库**精确一致**
  - **反思实测多次真实触发**：顺丰妥投率（修正分母口径）、注册城市≠收货城市
    （Critic 抓到多地址重复计数 → 按 is_default=1 去重，234 人）——都是语法自愈抓不到的语义错
  - 原 20 题回归：前 11 题一次成功率 100%（无回归）；**后 9 题因当日百炼免费额度耗尽（403）未跑完**，
    待额度重置后补跑（原 4 表口径未改，预期无回归）

## 第五阶段：多模态 + 模型切换 + 反思 A/B ✅ 2026-07-07 完成

> 动机：qwen-plus 免费额度耗尽，顺势换多模态模型，让 Agent 输入不再局限于文字；
> 并用 A/B 量化反思的真实收益（而非只讲故事）。

- [x] **LLM 客户端从百炼原生 starter 换成 Spring AI OpenAI 客户端 + 百炼「OpenAI 兼容端点」**
  （pom：去 spring-ai-alibaba-starter-dashscope，加 spring-ai-starter-model-openai；
  application.yml：spring.ai.openai.base-url=…/compatible-mode，model=**qwen3.7-plus**）：
  - 原因：qwen3.7-plus（多模态）只在兼容端点可用，原生端点 400；且 OpenAI 协议对流式 tool call、
    图片输入支持更规整，本项目流式聚合本就按 OpenAI 增量协议写，切换后零改动即兼容
  - chat 与 embedding（text-embedding-v4，1024 维）统一走兼容端点，一个客户端搞定
  - 坑：迁移中排查过——兼容端点 vs 原生端点对同一模型名接受度不同（qwen3.7-plus 仅前者认）
- [x] **多模态图片输入端到端打通**（ChatController 加 image 字段 → buildMedia 解析 data URI →
  AgentExecutor 多模态 UserMessage → qwen3.7-plus 读图）：
  - 测试 1（纯读图）：喂一张各品类销售额柱状图，准确读出 5 个品类数值 ✓
  - 测试 2（读图 + 查库对比，多模态 Agent 核心价值）：读图得"手机数码最高489万" → 自动
    schema_search+execute_sql 查库得实际 44.97 万 → 对比得"品类一致、数值差近10倍"并**自主推断
    差异原因**（时间范围/口径/数据源）✓。图片只挂首轮 user 消息，后续工具轮沿用同一消息列表
- [x] **原 20 题回归补跑完**（qwen3.7-plus）：SQL 一次成功率 **90%（18/20）**、自愈后 **100%**、
  任务完成率 **90%**、平均 **46.3s/题**（明显慢于 qwen-plus 的 ~14s，多模态模型迭代更啰嗦）
  - 2 例失败（城市Top5、周末工作日）均为**撞满 10 轮未收敛**，非检索错：根因是 **Critic 过度打回**
    ——把"各城市订单量"用注册城市的合理解法判为"该用收货城市"，带模型进 user_address 兔子洞反复重查
- [x] **反思开关 A/B 对照**（eval/questions-ab.txt 6 道语义陷阱题，真值全部手工核验；
  ab-reflect-ON.json / ab-reflect-OFF.json）：

  | 指标 | 反思 ON | 反思 OFF |
  |---|---|---|
  | 语义正确率 | 6/6 | 6/6 |
  | 反思判定 | 6× 通过、0 打回 | （关闭，轨迹实测 0 反思步骤） |
  | 平均耗时 | 38.5s | 38.6s |
  | 平均 SQL 尝试 | 1.17 | 1.17 |

  - **核心发现：反思的收益与底座模型强弱成反比**。qwen3.7-plus 强到这些陷阱题**首次即答对**，
    反思 6 次全"通过"、零打回 → 无正确率增益、成本相当；而在**更弱的 qwen-plus 上反思确有价值**
    （第四阶段 v2 实测：顺丰分母、多地址去重都靠反思修正）。反之在**模糊维度题上反思会帮倒忙**
    （上面回归的城市Top5 就是被 Critic 过度纠偏拖到超轮数）
  - 工程结论：反思做成**可配置开关**（agent.reflect.enabled）是对的；并已**收紧 Critic 提示词**
    （落在 AgentPrompts.CRITIC_SYSTEM_PROMPT）——只对高置信硬错打回（明列 total_amount 误用、
    漏有效订单过滤、时间字段用错、status 张冠李戴、数字对不上结果 5 类），并加一段
    "**以下情况一律判 pass**"：问题有多种合理口径解释（如"城市"用注册城市 users.city）、
    "换个口径也许更好"这类主观建议、拿不准的，一律放行，结尾"**存疑就通过**"——
    正是针对上面城市Top5/周末工作日被 Critic 过度纠偏拖到超轮数的两个 case
  - ⚠️ **待复跑坐实**：上表 AB（ON/OFF）与原 20 题回归都是拿**收紧前**的旧提示词跑的，
    "收紧后是否真消掉那两个 Critic 过度打回→超轮数的 case"还缺一次干净的复跑证明。
    此项已与第六阶段的复跑合并——额度恢复后一次复跑同时验证"收紧提示词"和"自适应路由"（详见第六阶段末）

## 第六阶段：自适应复杂度路由（Router → Executor → Critic 三角色）✅ 2026-07-08 完成

> 动机：phase-5 把"反思该不该开"做成了**全局开关**，但结论恰恰是"反思对硬口径题有用、
> 对模糊维度题（城市/周末）帮倒忙拖到超轮数"。全局一刀切满足不了这个分化——顺势升级成
> **进循环前按问题分档、按档位决定策略**，把那个两难从"全局"变成"按题"。也顺带把
> Critic（第二个 LLM 角色）之外补上 Router（第三个角色），凑成货真价实的角色特化型多 Agent 流水线。

- [x] **QuestionRouter 启发式分档**（agent/QuestionRouter + Tier + RouteDecision）：进 ReAct 循环前
  给问题分三档，产出执行策略（是否走工具 / maxRounds / 是否反思），决策记为 route 步骤（第 0 轮）落 trace：
  - `CHITCHAT`（问候/元问题）→ 不进循环、不检索、不反思，一次 LLM 直接答并把话题引回数据分析
  - `SIMPLE`（默认档：计数/明细 + **模糊维度题**）→ 走工具、maxRounds=7、**不反思**
    （主收益是跳过反思避开过度纠偏；轮数仅紧于 COMPLEX——手验发现会出图的简单题需
    检索+SQL+出图+收敛≈6 轮，初版设 5 会腰斩合法路径，改 7）
  - `COMPLEX`（命中硬口径词）→ 走工具、maxRounds=10、**开反思**
- [x] **判定零额外 LLM 调用**（省额度，本项目头号硬约束）：纯规则扫词 + 结构信号，确定、可解释。
  词表复用 schema 知识库业务术语，但**刻意分两组**把 phase-5 教训编码进规则：
  - **硬口径词**（销售额/GMV/动销率/退款率/核销率/妥投率/客单价/复购…）→ 升 COMPLEX + 反思：
    有唯一正确算法、错了不报错，正是 Critic 价值区
  - **模糊维度词**（城市/地区/周末/工作日）→ 只识别、不升档、不反思：一词多解，
    phase-5 里 Critic 恰恰对它们过度纠偏。注意"城市"在 schema 里也是口径词，
    天真全盘复用 TERMS 会误判成"敏感→开反思"踩中原坑，故硬口径词是**精选**的
  - CHITCHAT 判定三重收窄（命中闲聊词 + 问题短 ≤15 字 + 无查询信号），拿不准一律 SIMPLE 走工具
    （fail-safe：闲聊误判成查询顶多多跑几步，查询误判成闲聊会答非所问）
- [x] **agent.router.enabled 总开关**：关掉即退化回原行为（一律 COMPLEX 档 + 遵从全局 reflect），
  用于 A/B 对照"路由 ON vs OFF"——延续项目用数据说话的路子
- [x] **QuestionRouterTest 12 个纯单测**（无需 DB/LLM，即路由设计意图的可执行文档）：
  重点回归 phase-5 两个坑——城市Top5 / 周末工作日必落 SIMPLE 且不反思；硬口径题必升 COMPLEX 开反思；
  闲聊三重收窄的边界（带查询信号 / 问题过长都不判闲聊）；关闭路由退化为基线。**44 单测全绿、编译通过**
- [ ] ⚠️ **待复跑坐实**（与 phase-5 遗留合并）：Critic 收紧 + 本次路由都还没在**额度恢复后**跑过干净评测。
  待百炼额度恢复后用同一评测集（questions.txt / questions-ab.txt）复跑，预期：①城市Top5、周末工作日
  不再被反思拖到超轮数、任务完成率回 100%；②SIMPLE/CHITCHAT 档因省掉 Critic 调用，平均耗时与额度消耗下降；
  ③COMPLEX 档口径正确性不回退。这次复跑同时验证"收紧提示词"和"自适应路由"两件事

## 第七阶段（可选）：Text-to-SQL 小模型微调实验

> 前三阶段全部完成且有余力时再启动。本机 GPU 仅 4GB 显存（RTX 3050 Ti Laptop），**不能本地训练**，走云端。

- [ ] 用 qwen-max 蒸馏构造 Text-to-SQL 训练数据（基于本项目库表场景）
- [ ] 云端 LoRA 微调 Qwen 小模型：租 AutoDL GPU（4090 约 2 元/时）或用百炼托管微调
- [ ] 用第三阶段的自建评测集对比：微调小模型 vs qwen-plus 的 SQL 准确率 / 成本 / 延迟
- [ ] 简历表述目标："构造 X 条训练数据微调小模型，评测集准确率 A%→B%，推理成本降低 C 倍"

## 遗留事项 / 备忘

- 本机百炼 Key 存在用户环境变量 `API-KEY`（非标准名），application.yml 已做回退兼容
  `${DASHSCOPE_API_KEY:${API-KEY:}}`；建议手动补一个标准名变量：
  `setx DASHSCOPE_API_KEY %API-KEY%`（新终端生效）
- **百炼免费额度会被密集评测打满**：一次跑 40 题（每题多轮 LLM + Critic 审校 + embedding）
  约 36 次成功对话后触发 **403 Forbidden（当日配额耗尽，非 QPM 限流，冷却无效）**，需次日或充值恢复。
  跑全量评测建议分批、错开，或临时关 reflect（agent.reflect.enabled=false）省一半调用
- 反思每题多一次 LLM 调用（收敛前审校）：换来语义正确性，但推理调用量、延迟、配额消耗都上升，
  简历里要诚实提这个 trade-off
- 项目成型后把 GitHub 仓库从私有切换为公开（简历需要）
- gh CLI 安装在 `C:\Program Files\GitHub CLI\gh.exe`，新终端若提示找不到 gh 是 PATH 未刷新
