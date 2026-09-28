package com.ricardo.yuaiagent.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * RicardoManus（ReAct 超级智能体）的纯 Mock 测试：
 * 替换底层 ChatModel，验证智能体能正常跑完 ReAct 循环而不真调 API。
 */
@ExtendWith(MockitoExtension.class)
class RicardoManusTest {

    @Mock
    private ChatModel chatModel;

    @Test
    void run() {
        // 让模型始终返回「无需工具」，智能体一路「思考完成 - 无需行动」，最终因达到最大步数而正常终止
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("无需调用工具")))));

        RicardoManus ricardoManus = new RicardoManus(new ToolCallback[0], chatModel);
        String answer = ricardoManus.run("帮我做一件小事");

        assertNotNull(answer);
    }
}