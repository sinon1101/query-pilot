package com.yang.dataagent.agent;

import com.yang.dataagent.config.AgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 自适应复杂度路由器：进 ReAct 循环前给问题分档（{@link Tier}），产出 {@link RouteDecision}。
 * <p>
 * <b>为什么用启发式而不是 LLM 分类器</b>：判定零额外 LLM 调用（百炼免费额度是本项目头号硬约束），
 * 确定、可解释、可落 trace 讲"为什么这么路由"。判定词表复用 schema 知识库的业务术语单一数据源，
 * 但**刻意分成两组**，把 phase-5 的 A/B 教训直接编码进规则：
 * <ul>
 *   <li><b>硬口径词</b>（{@link #HARD_METRIC_TERMS}）→ COMPLEX + 开反思：
 *       销售额/GMV/动销率/退款率/核销率/妥投率/客单价/复购… 这些有唯一正确算法、
 *       算错还不报错（Critic 的价值区，phase-5 实测顺丰分母、多地址去重都靠反思修正）。</li>
 *   <li><b>模糊维度词</b>（{@link #FUZZY_DIMENSION_TERMS}）→ 不因它升档、不反思：
 *       城市/品类… 一词多解（注册地 vs 收货地）。phase-5 里 Critic 恰恰对这类问题过度纠偏，
 *       把"各城市订单量"判成"该用收货城市"带模型进 user_address 兔子洞反复重查直到超轮数。</li>
 * </ul>
 * 注意"城市"在 schema 知识库里也是业务口径词，天真地全盘复用 TERMS 会把它误判成"敏感→开反思"，
 * 恰好踩中那个坑——所以硬口径词是**精选**的，不含城市/品类/相对时间/库存这些歧义或低风险维度。
 * <p>
 * <b>判定保守（fail-safe）</b>：CHITCHAT 需三重收窄（命中闲聊词 + 问题短 + 无任何查询信号），
 * 拿不准一律落 SIMPLE 走工具——把闲聊误判成查询顶多多花几步，把查询误判成闲聊会答非所问。
 * <p>
 * router.enabled=false 时退化回原行为：一律 COMPLEX 档 + 遵从全局 reflect 开关，用于 A/B 对照。
 */
@Component
public class QuestionRouter {

    private static final Logger log = LoggerFactory.getLogger(QuestionRouter.class);

    /**
     * 硬口径词：命中即升 COMPLEX 并开反思。精选自 SchemaKnowledge.TERMS 中
     * "有唯一正确算法、错了不报错"的口径，剔除城市/品类/相对时间/库存等歧义或低风险项。
     */
    private static final List<String> HARD_METRIC_TERMS = List.of(
            "销售额", "gmv", "成交额", "营业额",
            "动销", "动销率",
            "退款率", "取消率",
            "核销", "核销率", "用券率",
            "履约", "妥投", "签收率", "拒收率",
            "客单价", "件单价",
            "复购", "复购率",
            "支付成功率", "实付",
            "会员渗透率");

    /**
     * 模糊维度词：一词多解，反思对它帮倒忙（phase-5 城市Top5/收货地兔子洞）。
     * 仅用于"识别出这是模糊题"，不作为升档依据，命中它们的问题留在 SIMPLE、不反思。
     */
    private static final List<String> FUZZY_DIMENSION_TERMS = List.of(
            "城市", "地区", "周末", "工作日");

    /** 闲聊/元问题信号词。命中且问题短、且无查询信号才判 CHITCHAT。 */
    private static final List<String> CHITCHAT_HINTS = List.of(
            "你好", "您好", "你是谁", "你能做什么", "能做什么", "怎么用", "如何使用",
            "帮助", "介绍一下你", "谢谢", "感谢", "再见");

    /** 查询信号：一旦命中就绝不判 CHITCHAT（宁可当查询多跑几步，也不答非所问）。 */
    private static final List<String> QUERY_SIGNALS = List.of(
            "多少", "哪个", "哪些", "几个", "排行", "排名", "top", "占比", "分布", "趋势",
            "最高", "最低", "最多", "最少", "平均", "统计", "查", "对比", "环比", "同比",
            "上个月", "本月", "今年", "去年", "最近", "各", "每");

    /** 短问题阈值（字符数）：超过它即便命中闲聊词也不判 CHITCHAT，多半是夹带了真问题。 */
    private static final int CHITCHAT_MAX_LEN = 15;

    private final AgentProperties props;

    public QuestionRouter(AgentProperties props) {
        this.props = props;
    }

    public RouteDecision route(String question) {
        AgentProperties.Router cfg = props.router();

        // 路由关闭：退化回原行为，A/B 对照的基线
        if (cfg == null || !cfg.enabled()) {
            boolean reflect = props.reflect().enabled();
            return new RouteDecision(Tier.COMPLEX, true, reflect, props.maxRounds(),
                    "路由已关闭，退化为 COMPLEX 档 + 全局 reflect(" + reflect + ")");
        }

        String q = question == null ? "" : question.toLowerCase();

        // 1) 硬口径词优先：命中即 COMPLEX + 反思（即便问题很短也不会误判成闲聊）
        String hit = firstMatch(q, HARD_METRIC_TERMS);
        if (hit != null) {
            return new RouteDecision(Tier.COMPLEX, true, true, cfg.complexMaxRounds(),
                    "命中硬口径词「" + hit + "」，需精确口径，开反思");
        }

        // 2) 闲聊短路：命中闲聊词 + 问题短 + 无任何查询信号，三者同时满足才判（保守）
        String chitchatHit = firstMatch(q, CHITCHAT_HINTS);
        String querySignal = firstMatch(q, QUERY_SIGNALS);
        if (chitchatHit != null && q.length() <= CHITCHAT_MAX_LEN && querySignal == null) {
            return new RouteDecision(Tier.CHITCHAT, false, false, 0,
                    "命中闲聊词「" + chitchatHit + "」且问题短、无查询信号，直接作答");
        }

        // 3) 其余一律 SIMPLE：走工具但收紧轮数、不反思。
        //    模糊维度题（城市/周末）在此落地——正是要让它们不进反思，避开过度纠偏
        String fuzzy = firstMatch(q, FUZZY_DIMENSION_TERMS);
        String reason = fuzzy != null
                ? "含模糊维度「" + fuzzy + "」（一词多解），走工具但不反思以避免过度纠偏"
                : "常规查询，走工具、收紧轮数、不反思";
        return new RouteDecision(Tier.SIMPLE, true, false, cfg.simpleMaxRounds(), reason);
    }

    /** 返回 terms 中第一个作为子串出现在 q 里的词（q 已转小写；词表本身含小写形态），无命中返回 null */
    private static String firstMatch(String q, List<String> terms) {
        for (String t : terms) {
            if (q.contains(t)) {
                return t;
            }
        }
        return null;
    }
}
