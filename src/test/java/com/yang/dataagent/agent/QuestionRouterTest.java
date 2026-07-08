package com.yang.dataagent.agent;

import com.yang.dataagent.config.AgentProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QuestionRouter 纯单元测试——同时充当路由设计意图的可执行文档。
 * 重点回归 phase-5 的两个坑：城市Top5 / 周末工作日必须落 SIMPLE 且不反思，
 * 而硬口径题（销售额/GMV/动销/退款率/妥投）必须升 COMPLEX 并开反思。
 */
class QuestionRouterTest {

    /** 路由开启 + 全局 reflect 开启（reflect 只在路由关闭时才被读到，作基线用） */
    private static QuestionRouter router(boolean routerEnabled) {
        AgentProperties props = new AgentProperties(
                10,
                new AgentProperties.Sql(3, 10, 200),
                new AgentProperties.BizDatasource("url", "u", "p"),
                new AgentProperties.Rag("localhost", 6380, 8, 20),
                new AgentProperties.Memory(20),
                new AgentProperties.Reflect(true, 1),
                new AgentProperties.Router(routerEnabled, 7, 10));
        return new QuestionRouter(props);
    }

    private static QuestionRouter router() {
        return router(true);
    }

    // ---------- COMPLEX：硬口径词升档 + 开反思（反思的价值区）----------

    @Test
    void salesRevenueIsComplexWithReflection() {
        RouteDecision d = router().route("上个月哪个品类销售额最高");
        assertThat(d.tier()).isEqualTo(Tier.COMPLEX);
        assertThat(d.reflectEnabled()).isTrue();
        assertThat(d.usesTools()).isTrue();
        assertThat(d.maxRounds()).isEqualTo(10);
    }

    @Test
    void gmvIsComplex() {
        assertThat(router().route("今年整体 GMV 是多少").tier()).isEqualTo(Tier.COMPLEX);
    }

    @Test
    void deliveryAndRefundRatesAreComplex() {
        assertThat(router().route("顺丰的妥投率是多少").tier()).isEqualTo(Tier.COMPLEX);
        assertThat(router().route("整体退款率怎么样").tier()).isEqualTo(Tier.COMPLEX);
        assertThat(router().route("优惠券核销率").tier()).isEqualTo(Tier.COMPLEX);
        assertThat(router().route("各品类的动销率").tier()).isEqualTo(Tier.COMPLEX);
    }

    // ---------- phase-5 两个坑的回归：模糊维度题落 SIMPLE 且不反思 ----------

    @Test
    void cityTopNIsSimpleNoReflection() {
        // phase-5：Critic 曾把"各城市订单量"判成"该用收货城市"拖到超轮数。必须不反思。
        RouteDecision d = router().route("哪个城市的订单量最多");
        assertThat(d.tier()).isEqualTo(Tier.SIMPLE);
        assertThat(d.reflectEnabled()).isFalse();
        assertThat(d.usesTools()).isTrue();
        assertThat(d.maxRounds()).isEqualTo(7);
        assertThat(d.reason()).contains("模糊维度");
    }

    @Test
    void weekendWeekdayIsSimpleNoReflection() {
        RouteDecision d = router().route("周末和工作日的订单量对比");
        assertThat(d.tier()).isEqualTo(Tier.SIMPLE);
        assertThat(d.reflectEnabled()).isFalse();
    }

    // ---------- SIMPLE：常规查询（无硬口径词）----------

    @Test
    void plainCountIsSimple() {
        RouteDecision d = router().route("一共有多少个用户");
        assertThat(d.tier()).isEqualTo(Tier.SIMPLE);
        assertThat(d.reflectEnabled()).isFalse();
    }

    @Test
    void listingIsSimple() {
        assertThat(router().route("列出最新的10个订单").tier()).isEqualTo(Tier.SIMPLE);
    }

    // ---------- CHITCHAT：闲聊短路（三重收窄）----------

    @Test
    void greetingIsChitchat() {
        RouteDecision d = router().route("你好");
        assertThat(d.tier()).isEqualTo(Tier.CHITCHAT);
        assertThat(d.usesTools()).isFalse();
        assertThat(d.reflectEnabled()).isFalse();
    }

    @Test
    void metaQuestionIsChitchat() {
        assertThat(router().route("你能做什么").tier()).isEqualTo(Tier.CHITCHAT);
    }

    @Test
    void chitchatWordButWithQuerySignalIsNotChitchat() {
        // 命中"你好"但带查询信号"多少" → 不能短路成闲聊，宁可当查询走工具
        RouteDecision d = router().route("你好，请问一共有多少订单");
        assertThat(d.tier()).isNotEqualTo(Tier.CHITCHAT);
        assertThat(d.usesTools()).isTrue();
    }

    @Test
    void longChitchatWordedQuestionIsNotChitchat() {
        // 命中闲聊词但问题很长（夹带真问题）→ 不判闲聊
        RouteDecision d = router().route("你好呀我想了解一下贵公司这套系统到底能不能帮我分析销售数据呢");
        assertThat(d.tier()).isNotEqualTo(Tier.CHITCHAT);
    }

    // ---------- 路由关闭：退化为 COMPLEX 档 + 遵从全局 reflect（A/B 基线）----------

    @Test
    void disabledRouterFallsBackToComplexWithGlobalReflect() {
        QuestionRouter r = router(false);
        // 即便是闲聊句，关闭路由后也一律 COMPLEX + 走工具 + 全局 reflect(true) + 全局 maxRounds(10)
        RouteDecision d = r.route("你好");
        assertThat(d.tier()).isEqualTo(Tier.COMPLEX);
        assertThat(d.usesTools()).isTrue();
        assertThat(d.reflectEnabled()).isTrue();
        assertThat(d.maxRounds()).isEqualTo(10);

        // 模糊维度题在关闭路由后同样退化（反思会重新生效，即 phase-5 的旧行为）
        assertThat(r.route("哪个城市的订单量最多").reflectEnabled()).isTrue();
    }
}
