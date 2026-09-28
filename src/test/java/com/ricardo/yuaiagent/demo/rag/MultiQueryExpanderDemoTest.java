package com.ricardo.yuaiagent.demo.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.rag.Query;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 查询扩展器 Demo 的纯 Mock 测试：替换 ChatModel，验证查询扩展能返回结果而不真调 API。
 */
@ExtendWith(MockitoExtension.class)
class MultiQueryExpanderDemoTest {

    @Mock
    private ChatModel chatModel;

    @Test
    void expand() {
        // MultiQueryExpander 要求模型按行返回恰好 numberOfQueries(3) 个查询变体，否则回退原查询
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(
                        "Java 后端面试该如何准备\nJava 面试高频知识点有哪些\n如何系统提升 Java 面试能力"
                )))));

        MultiQueryExpanderDemo demo = new MultiQueryExpanderDemo(chatModel);
        List<Query> queries = demo.expand("什么是 Java 面试");

        assertNotNull(queries);
    }
}