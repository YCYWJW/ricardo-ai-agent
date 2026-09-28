package com.ricardo.yuaiagent.app;

import com.ricardo.yuaiagent.resume.ResumeStore;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    // 简历内存缓存：测试里往会话塞一份「假简历」，用于验证简历原文绝不进 SSE 事件
    @Resource
    private ResumeStore resumeStore;

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

    /**
     * 红线测试：思考链（thought）事件绝不能泄露简历原文或检索到的文档原文。
     * 验证方式：塞入明显敏感的「假简历」+「假知识库文档」，跑完 doChatByStream 后
     * 逐条检查所有 SSE 事件，断言敏感字样一个都不出现，且 thought 只汇报命中条数。
     */
    @Test
    void doChatByStream_思考链事件绝不泄露原文() {
        String chatId = UUID.randomUUID().toString();

        // 假简历：含姓名、手机号、邮箱等明显敏感信息（只进内存缓存，不应出现在任何事件里）
        resumeStore.put(chatId, "张三，电话13800000000，邮箱 zhangsan@example.com");
        // 假知识库文档：同样含敏感字样（会被拼进给模型的 prompt，但绝不能出现在事件里）
        when(interviewAppVectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(new Document("李四的私密资料：身份证号 110101199001011234")));

        // mock 流式模型输出（stream 走的是 ChatModel.stream，而不是 call）
        when(dashscopeChatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.just(new ChatResponse(List.of(
                        new Generation(new AssistantMessage("这是模拟的面试官回答"))
                ))));

        // 收集这一轮的全部 SSE 事件
        List<String> events = interviewApp.doChatByStream("请评价一下我的简历", chatId)
                .collectList()
                .block();

        assertNotNull(events);

        // 1. 三类事件都应出现
        assertTrue(events.stream().anyMatch(e -> e.contains("\"type\":\"thought\"")), "应包含 thought 事件");
        assertTrue(events.stream().anyMatch(e -> e.contains("\"type\":\"text\"")), "应包含 text 事件");
        assertTrue(events.stream().anyMatch(e -> e.contains("\"type\":\"done\"")), "应包含 done 事件");

        // 2. 红线：任何事件里都不得出现简历/检索原文的敏感片段
        for (String event : events) {
            assertFalse(event.contains("张三"), "事件泄露了简历姓名：" + event);
            assertFalse(event.contains("13800000000"), "事件泄露了简历手机号：" + event);
            assertFalse(event.contains("zhangsan@example.com"), "事件泄露了简历邮箱：" + event);
            assertFalse(event.contains("李四"), "事件泄露了检索原文：" + event);
            assertFalse(event.contains("110101199001011234"), "事件泄露了检索原文身份证号：" + event);
        }

        // 3. thought 事件只汇报「命中条数」，不含原文
        assertTrue(events.stream().anyMatch(e -> e.contains("命中 1 条")), "thought 应汇报真实命中条数");
    }

    /**
     * 自动开场测试：前端在「简历上传成功」后发送 __SYSTEM__ 触发消息，
     * 后端应进入自动开场分支——读简历、准备开场问题，并且不去检索题库
     * （触发消息本身不含有效检索语义，检索只会塞进无关内容）。
     * 同时守住红线：触发消息与所有 SSE 事件中都不含简历原文。
     */
    @Test
    void doChatByStream_上传简历后自动开场() {
        String chatId = UUID.randomUUID().toString();
        // 假简历：含姓名、手机号、邮箱、项目名等敏感字样
        resumeStore.put(chatId, "张三，电话13800000000，邮箱 zhangsan@example.com，项目：分布式订单中心系统");

        when(dashscopeChatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.just(new ChatResponse(List.of(
                        new Generation(new AssistantMessage("我看到你的简历了，我们先聊聊订单中心项目"))
                ))));

        List<String> events = interviewApp.doChatByStream(
                        "__SYSTEM__: 我已经上传了简历，请阅读后开始面试", chatId)
                .collectList()
                .block();

        assertNotNull(events);

        // 1. 走的必须是自动开场分支：思考链是「读简历」语义
        assertTrue(events.stream().anyMatch(e -> e.contains("正在阅读你的简历")), "应进入自动开场分支");

        // 2. 自动开场不检索题库，因此不该出现检索相关文案与命中条数
        assertFalse(events.stream().anyMatch(e -> e.contains("正在检索知识库")), "自动开场不应检索题库");
        assertFalse(events.stream().anyMatch(e -> e.contains("命中 ")), "自动开场不应汇报命中条数");

        // 3. 开场同样要有完整的 text / done 事件，前端才能正常流式渲染与收尾
        assertTrue(events.stream().anyMatch(e -> e.contains("\"type\":\"text\"")), "应包含 text 事件");
        assertTrue(events.stream().anyMatch(e -> e.contains("\"type\":\"done\"")), "应包含 done 事件");

        // 4. 红线：任何事件都不得出现简历原文，也不得把系统前缀回传给前端
        for (String event : events) {
            assertFalse(event.contains("张三"), "事件泄露了简历姓名：" + event);
            assertFalse(event.contains("13800000000"), "事件泄露了简历手机号：" + event);
            assertFalse(event.contains("zhangsan@example.com"), "事件泄露了简历邮箱：" + event);
            assertFalse(event.contains("分布式订单中心系统"), "事件泄露了简历项目名：" + event);
            assertFalse(event.contains("__SYSTEM__"), "系统触发前缀不应出现在事件里：" + event);
        }

        // 5. 确认真的跳过了向量检索：一次都没有调用
        verify(interviewAppVectorStore, never()).similaritySearch(any(SearchRequest.class));
    }

    /**
     * 边界测试：即使收到带系统前缀的消息，只要该会话没有简历，也不能走自动开场分支。
     * 否则面试官会对着空气说「我看到你的简历了」，体验错乱。
     */
    @Test
    void doChatByStream_无简历时系统前缀不触发开场() {
        String chatId = UUID.randomUUID().toString(); // 刻意不塞简历

        when(dashscopeChatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.just(new ChatResponse(List.of(
                        new Generation(new AssistantMessage("这是模拟的面试官回答"))
                ))));

        List<String> events = interviewApp.doChatByStream(
                        "__SYSTEM__: 我已经上传了简历，请阅读后开始面试", chatId)
                .collectList()
                .block();

        assertNotNull(events);
        // 没有简历时应退回常规分支（正常检索并回答），而不是自动开场
        assertTrue(events.stream().anyMatch(e -> e.contains("正在分析你的问题")), "无简历时应走常规分支");
        assertFalse(events.stream().anyMatch(e -> e.contains("正在阅读你的简历")), "无简历时不应进入自动开场");
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