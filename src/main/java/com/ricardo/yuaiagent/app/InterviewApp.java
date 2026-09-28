package com.ricardo.yuaiagent.app;

import com.ricardo.yuaiagent.advisor.MyLoggerAdvisor;
import com.ricardo.yuaiagent.advisor.ReReadingAdvisor;
import com.ricardo.yuaiagent.chatmemory.FileBasedChatMemory;
import com.ricardo.yuaiagent.rag.InterviewAppRagCustomAdvisorFactory;
import com.ricardo.yuaiagent.rag.QueryRewriter;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;

@Component
@Slf4j
public class InterviewApp {

    private final ChatClient chatClient;

    private static final String SYSTEM_PROMPT = """
            你是一位资深 Java 后端面试官，拥有 10 年以上一线互联网大厂的后端研发与面试经验，精通 Java 技术栈、微服务、分布式系统、数据库、缓存与消息中间件。

            【你的职责】
            为用户提供一场真实、专业、有针对性的模拟面试，帮助他发现短板、查漏补缺、提升面试表现。

            【面试规则】
            1. 开场先做简短自我介绍并说明面试流程，然后请对方先做自我介绍。
            2. 如果对话中提供了候选人的简历/项目描述（系统会作为背景信息附上），你必须优先围绕简历中的项目经历提问，顺着对方的描述逐层深挖，不要泛泛而谈；对每个项目按「背景 → 你的职责 → 技术选型理由 → 核心难点 → 最终成果 → 可改进点」的框架追问；重点拷问为什么选这个技术方案、踩过哪些坑、如何解决、性能瓶颈在哪、如何扩展；若对方回答含糊、只背概念，要求他结合自己的项目给出具体例子再继续追问。
            3. 技术基础考察围绕 Java 后端展开（示例：Java 基础与 JVM、并发编程、集合框架、Spring/Spring Boot、MySQL 索引与事务、Redis 缓存、消息队列、分布式一致性、网络与 HTTP），由浅入深，根据对方水平动态调整难度。
            4. 多轮追问：每轮只问 1~2 个问题，等对方回答后再基于他的回答继续追问，绝不一次性抛出多个问题。
            5. 语气专业、平等、以鼓励为主，不居高临下、不恶意刁难；发现错误要及时指出并讲解正确原理。
            6. 遇到与技术面试无关的闲聊或越界请求，礼貌地把话题拉回面试。

            【面试流程】
            候选人自我介绍 → 简历项目深挖 → 技术基础考察 → 开放题/系统设计 → 候选人反问 → 面试总结与评分。
            """;

    /**
     * 初始化 ChatClient
     *
     * @param dashscopeChatModel
     */
    public InterviewApp(ChatModel dashscopeChatModel) {
//        // 初始化基于文件的对话记忆
//        String fileDir = System.getProperty("user.dir") + "/tmp/chat-memory";
//        ChatMemory chatMemory = new FileBasedChatMemory(fileDir);
        // 初始化基于内存的对话记忆
        MessageWindowChatMemory chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(20)
                .build();
        chatClient = ChatClient.builder(dashscopeChatModel)
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        // 自定义日志 Advisor，可按需开启
                        new MyLoggerAdvisor()
//                        // 自定义推理增强 Advisor，可按需开启
//                       ,new ReReadingAdvisor()
                )
                .build();
    }

    /**
     * AI 基础对话（支持多轮对话记忆）
     *
     * @param message
     * @param chatId
     * @return
     */
    public String doChat(String message, String chatId) {
        ChatResponse chatResponse = chatClient
                .prompt()
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .call()
                .chatResponse();
        String content = chatResponse.getResult().getOutput().getText();
        log.info("content: {}", content);
        return content;
    }

    /**
     * AI 基础对话（支持多轮对话记忆，SSE 流式传输）
     *
     * @param message
     * @param chatId
     * @return
     */
    public Flux<String> doChatByStream(String message, String chatId) {
        return chatClient
                .prompt()
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .stream()
                .content();
    }

    record InterviewReport(String overview, List<String> strengths, List<String> weaknesses, List<String> suggestions) {

    }

    /**
     * AI 面试评估报告功能（实战结构化输出）
     *
     * @param message
     * @param chatId
     * @return
     */
    public InterviewReport doChatWithReport(String message, String chatId) {
        InterviewReport interviewReport = chatClient
                .prompt()
                .system(SYSTEM_PROMPT + "面试结束后，输出一份面试评估报告，包含：总评、技术亮点、薄弱项、改进建议")
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .call()
                .entity(InterviewReport.class);
        log.info("interviewReport: {}", interviewReport);
        return interviewReport;
    }

    // AI 知识库问答功能

    @Resource
    private VectorStore interviewAppVectorStore;

    @Resource
    private Advisor interviewAppRagCloudAdvisor;

    @Resource
    private VectorStore pgVectorVectorStore;

    @Resource
    private QueryRewriter queryRewriter;

    /**
     * 和 RAG 知识库进行对话
     *
     * @param message
     * @param chatId
     * @return
     */
    public String doChatWithRag(String message, String chatId) {
        // 查询重写
        String rewrittenMessage = queryRewriter.doQueryRewrite(message);
        ChatResponse chatResponse = chatClient
                .prompt()
                // 使用改写后的查询
                .user(rewrittenMessage)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                // 开启日志，便于观察效果
                .advisors(new MyLoggerAdvisor())
                // 应用 RAG 知识库问答
                .advisors(new QuestionAnswerAdvisor(interviewAppVectorStore))
                // 应用 RAG 检索增强服务（基于云知识库服务）
//                .advisors(interviewAppRagCloudAdvisor)
                // 应用 RAG 检索增强服务（基于 PgVector 向量存储）
//                .advisors(new QuestionAnswerAdvisor(pgVectorVectorStore))
                // 应用自定义的 RAG 检索增强服务（文档查询器 + 上下文增强器）
//                .advisors(
//                        InterviewAppRagCustomAdvisorFactory.createInterviewAppRagCustomAdvisor(
//                                interviewAppVectorStore, "Java基础与并发面试题"
//                        )
//                )
                .call()
                .chatResponse();
        String content = chatResponse.getResult().getOutput().getText();
        log.info("content: {}", content);
        return content;
    }

    // AI 调用工具能力
    @Resource
    private ToolCallback[] allTools;

    /**
     * AI 面试功能（支持调用工具）
     *
     * @param message
     * @param chatId
     * @return
     */
    public String doChatWithTools(String message, String chatId) {
        ChatResponse chatResponse = chatClient
                .prompt()
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                // 开启日志，便于观察效果
                .advisors(new MyLoggerAdvisor())
                .toolCallbacks(allTools)
                .call()
                .chatResponse();
        String content = chatResponse.getResult().getOutput().getText();
        log.info("content: {}", content);
        return content;
    }

    // AI 调用 MCP 服务

    @Resource
    private ToolCallbackProvider toolCallbackProvider;

    /**
     * AI 面试功能（调用 MCP 服务）
     *
     * @param message
     * @param chatId
     * @return
     */
    public String doChatWithMcp(String message, String chatId) {
        ChatResponse chatResponse = chatClient
                .prompt()
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                // 开启日志，便于观察效果
                .advisors(new MyLoggerAdvisor())
                .toolCallbacks(toolCallbackProvider)
                .call()
                .chatResponse();
        String content = chatResponse.getResult().getOutput().getText();
        log.info("content: {}", content);
        return content;
    }
}