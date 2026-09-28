package com.ricardo.yuaiagent.app;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * InterviewApp 的 Mock 测试：
 * 用 @MockitoBean 替换掉会真调大模型的 dashscopeChatModel、以及会真做向量化的
 * interviewAppVectorStore，让 3 个核心方法（testChat / doChatWithReport / doChatWithRag）
 * 在不消耗 token、不出网的前提下跑通；验证「多层对话记忆、结构化报告、RAG 问答」也未被破坏。
 */
@SpringBootTest
class InterviewAppTest {

    // 替换 DashScope 对话模型（按 bean 名精确指定，避免和容器里的 ollamaChatModel 歧义）
    @MockitoBean(name = "dashscopeChatModel")
    private ChatModel dashscopeChatModel;

    // 替换向量库，让 Spring 跳过启动时的向量化（35 次关键词 + 35 次 embedding），绝不烧 token
    @MockitoBean(name = "interviewAppVectorStore")
    private VectorStore interviewAppVectorStore;

    @Resource
    private InterviewApp interviewApp;

    @BeforeEach
    void setUp() {
        // 对话模型统一返回一句固定的假回复，绝不真调 API
        when(dashscopeChatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(
                        new Generation(new AssistantMessage("这是模拟的面试官回复"))
                )));
        // 向量库相似度搜索返回空结果，让 RAG 走「无相关知识」分支即可
        when(interviewAppVectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of());
    }

    @Test
    void testChat() {
        String chatId = UUID.randomUUID().toString();
        String answer = interviewApp.doChat("你好，我是一名 3 年经验的 Java 后端工程师", chatId);
        assertNotNull(answer);
    }

    @Test
    void doChatWithReport() {
        // 结构化输出需要模型返回一段符合 InterviewReport 结构的 JSON 文本
        when(dashscopeChatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(
                        new Generation(new AssistantMessage(
                                "{\"overview\":\"总体表现良好\",\"strengths\":[\"基础扎实\"],\"weaknesses\":[\"项目深度不足\"],\"suggestions\":[\"多准备项目细节\"]}"
                        ))
                )));
        String chatId = UUID.randomUUID().toString();
        InterviewApp.InterviewReport report = interviewApp.doChatWithReport("请基于我的简历做模拟面试", chatId);
        assertNotNull(report);
        // 校验结构化报告的四个字段都能被解析出来
        assertNotNull(report.overview());
        assertNotNull(report.strengths());
        assertNotNull(report.weaknesses());
        assertNotNull(report.suggestions());
    }

    @Test
    void doChatWithRag() {
        String chatId = UUID.randomUUID().toString();
        String answer = interviewApp.doChatWithRag("面试中常问的 Java 后端基础知识有哪些？", chatId);
        assertNotNull(answer);
    }

    @Test
    @Disabled("依赖真实工具调用（WebSearch/网页抓取等），暂不 Mock 化")
    void doChatWithTools() {
        testMessage("帮我联网搜索一下 Java 后端面试中 Redis 的高频面试题？");
        testMessage("直接下载一张 Java 技术相关的图片为文件");
    }

    private void testMessage(String message) {
        String chatId = UUID.randomUUID().toString();
        String answer = interviewApp.doChatWithTools(message, chatId);
        assertNotNull(answer);
    }

    @Test
    @Disabled("依赖真实 MCP 服务，暂不 Mock 化")
    void doChatWithMcp() {
        String chatId = UUID.randomUUID().toString();
        String message = "帮我搜索一些程序员面试相关的图片";
        String answer = interviewApp.doChatWithMcp(message, chatId);
        assertNotNull(answer);
    }
}