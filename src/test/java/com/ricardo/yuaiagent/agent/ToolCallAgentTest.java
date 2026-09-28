package com.ricardo.yuaiagent.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * ToolCallAgent（ReAct 循环核心）的纯 Mock 测试：
 * 替换底层 ChatModel，专门验证「无工具时正常返回 false」和「模型异常时的降级 + maxSteps 终止」，
 * 全程不出网、不烧 token。
 */
@ExtendWith(MockitoExtension.class)
class ToolCallAgentTest {

    @Mock
    private ChatModel chatModel;

    private ToolCallAgent agent;

    @BeforeEach
    void setUp() {
        ChatClient chatClient = ChatClient.builder(chatModel).build();
        agent = new ToolCallAgent(new ToolCallback[0]);
        agent.setName("testAgent");
        agent.setChatClient(chatClient);
        agent.setSystemPrompt("你是测试用的智能体");
        agent.setNextStepPrompt("请根据用户需求选择最合适的工具");
        agent.setMaxSteps(3); // 缩小步数，加速测试
    }

    @Test
    void think_模型说无需工具时_返回false() {
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("无需调用工具")))));

        assertFalse(agent.think());
    }

    @Test
    void think_模型异常时_降级返回false不崩溃() {
        when(chatModel.call(any(Prompt.class)))
                .thenThrow(new RuntimeException("模拟大模型接口故障"));

        assertFalse(agent.think(), "模型异常时 think 应降级返回 false 而不是抛出异常");
    }

    @Test
    void run_模型持续异常时_循环因达到最大步数而正常终止() {
        when(chatModel.call(any(Prompt.class)))
                .thenThrow(new RuntimeException("模拟大模型接口故障"));

        String result = agent.run("帮我做点事");

        assertNotNull(result);
        assertTrue(result.contains("Terminated"), "即使模型持续异常，也应通过达到最大步数正常终止，而不是崩溃");
    }
}