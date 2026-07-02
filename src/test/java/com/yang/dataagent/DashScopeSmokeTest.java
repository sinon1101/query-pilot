package com.yang.dataagent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 百炼 qwen-plus 连通性冒烟测试。
 * 需要环境变量 DASHSCOPE_API_KEY 且本地 MySQL(3307) 已启动，未配置时自动跳过。
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "DASHSCOPE_API_KEY", matches = ".+")
class DashScopeSmokeTest {

    @Autowired
    private ChatModel chatModel;

    @Test
    void qwenPlusResponds() {
        String reply = chatModel.call("请只回复两个字：收到");
        System.out.println("qwen-plus 回复: " + reply);
        assertThat(reply).isNotBlank();
    }
}
