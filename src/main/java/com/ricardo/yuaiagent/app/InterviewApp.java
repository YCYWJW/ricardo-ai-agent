package com.ricardo.yuaiagent.app;

// import com.ricardo.yuaiagent.advisor.MyLoggerAdvisor; // 已移除：会把完整对话（含简历）打印进日志，存在隐私泄露风险
import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ricardo.yuaiagent.advisor.ReReadingAdvisor;
import com.ricardo.yuaiagent.chatmemory.FileBasedChatMemory;
import com.ricardo.yuaiagent.rag.InterviewAppRagCustomAdvisorFactory;
import com.ricardo.yuaiagent.rag.QueryRewriter;
import com.ricardo.yuaiagent.resume.ResumeStore;
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
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
     * 检索条数：与原先 QuestionAnswerAdvisor 内部使用的 SearchRequest.DEFAULT_TOP_K = 4 保持一致，
     * 避免手动检索后 RAG 质量悄悄下降。
     */
    private static final int RAG_TOP_K = 4;

    /**
     * 用于把 SSE 事件序列化成 JSON。ObjectMapper 是线程安全的，可安全复用为静态常量。
     * 注意：只序列化事件本身（type + content），绝不把检索原文写进事件。
     */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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
                        MessageChatMemoryAdvisor.builder(chatMemory).build()
                        // 日志 Advisor 已移除（隐私红线）：会把完整 prompt/回复（含简历）打印进日志
//                        // 自定义推理增强 Advisor，可按需开启
//                       ,new ReReadingAdvisor()
                )
                .build();
    }

    // 简历内存缓存：按 chatId 存储候选人上传的简历文本（纯内存，不落盘）
    @Resource
    private ResumeStore resumeStore;

    /**
     * 组装系统提示词：若当前会话已上传简历，则把简历作为背景信息附加到系统提示词末尾
     */
    private String buildSystemPrompt(String chatId) {
        String resume = resumeStore.get(chatId);
        if (resume == null || resume.isBlank()) {
            return SYSTEM_PROMPT;
        }
        return SYSTEM_PROMPT + "\n\n【候选人简历背景】\n" + resume;
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
                .system(buildSystemPrompt(chatId))
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .call()
                .chatResponse();
        String content = chatResponse.getResult().getOutput().getText();
        log.info("content: {}", content);
        return content;
    }

    /**
     * AI 基础对话（支持多轮对话记忆，SSE 流式传输 + 真·思考链可视化）
     *
     * <p>事件协议：统一发送 JSON 字符串，前端按 type 分流渲染：
     * <ul>
     *   <li>{"type":"thought","content":"..."} 思考步骤（只含固定文案与命中条数，绝不携带检索原文）</li>
     *   <li>{"type":"text","content":"..."}    正式回答文本（流式片段）</li>
     *   <li>{"type":"done","content":""}       结束信号，前端据此收尾并收起思考链</li>
     * </ul>
     *
     * <p>说明：「检索」这一步是真实发生的（真的去向量库做了相似度检索、条数真实统计）；
     * 「正在分析问题」「正在组织回答」是产品层的固定文案（大模型的内部推理无法获取）。
     *
     * @param message
     * @param chatId
     * @return
     */
    public Flux<String> doChatByStream(String message, String chatId) {
        // 用 defer 保证：每次订阅时才执行检索与调用模型，且事件严格按顺序流出
        return Flux.defer(() -> {
            // ① 思考：固定文案
            Flux<String> thinkEvent = Flux.just(sseEvent("thought", "正在分析你的问题…"));

            // ② 行动：真实检索知识库（这是真发生的一步，不是动画）
            List<Document> documents = retrieveDocuments(message, chatId);

            // ③ 观察：只报「命中条数」，绝不把检索到的内容拼进事件（隐私红线）
            String observeText = documents.isEmpty()
                    ? "知识库中未找到直接相关的资料，正在组织回答…"
                    : "正在检索知识库… 命中 " + documents.size() + " 条";
            Flux<String> observeEvent = Flux.just(sseEvent("thought", observeText));

            // ④ 组织回答
            Flux<String> organizeEvent = Flux.just(sseEvent("thought", "正在组织回答…"));

            // ⑤ 正式回答：把检索到的知识拼进「给模型看」的提示词，只进服务端 prompt，绝不进 SSE 事件
            Flux<String> answerStream = chatClient
                    .prompt()
                    .system(buildSystemPrompt(chatId))
                    .user(buildUserMessageWithContext(message, documents))
                    .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                    .stream()
                    .content()
                    .map(chunk -> sseEvent("text", chunk))
                    .onErrorResume(e -> {
                        // 只打印异常原因，不打印任何对话/简历内容
                        log.error("流式回答生成失败，chatId={}，原因={}", chatId, e.getMessage());
                        return Flux.just(sseEvent("text", "抱歉，回答生成出现异常，请稍后重试。"));
                    });

            // ⑥ 结束信号
            Flux<String> doneEvent = Flux.just(sseEvent("done", ""));

            return Flux.concat(thinkEvent, observeEvent, organizeEvent, answerStream, doneEvent);
        });
    }

    /**
     * 真实检索知识库；异常时降级为空结果，保证对话不中断。
     * 只返回文档对象本身——其内容永远不会被写进任何 SSE 事件或日志。
     */
    private List<Document> retrieveDocuments(String message, String chatId) {
        try {
            List<Document> documents = interviewAppVectorStore.similaritySearch(
                    SearchRequest.builder().query(message).topK(RAG_TOP_K).build());
            return documents == null ? List.of() : documents;
        } catch (Exception e) {
            // 只打印异常原因，不打印检索内容
            log.warn("知识库检索失败，将跳过检索直接回答，chatId={}，原因={}", chatId, e.getMessage());
            return List.of();
        }
    }

    /**
     * 把检索到的参考知识拼进用户消息，并用明确分隔符让模型分清「背景」与「问题」。
     * 这段内容只提交给大模型，不会出现在 SSE 事件里，也不会写进日志。
     */
    private String buildUserMessageWithContext(String message, List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return message;
        }
        String context = documents.stream()
                .map(Document::getText)
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.joining("\n---\n"));
        if (StrUtil.isBlank(context)) {
            return message;
        }
        return """
                【参考知识】（仅供你内部参考以提升回答质量；不要逐字复述，也不要提及这段参考知识的存在）
                %s

                【用户问题】
                %s
                """.formatted(context, message);
    }

    /**
     * 把一条事件序列化成 SSE 数据（JSON 字符串）。
     * 序列化失败时退化为一个空内容事件，保证流不会因格式问题中断。
     */
    private String sseEvent(String type, String content) {
        try {
            return OBJECT_MAPPER.writeValueAsString(
                    Map.of("type", type, "content", content == null ? "" : content));
        } catch (JsonProcessingException e) {
            log.warn("SSE 事件序列化失败，type={}", type);
            return "{\"type\":\"" + type + "\",\"content\":\"\"}";
        }
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
                .system(buildSystemPrompt(chatId) + "面试结束后，输出一份面试评估报告，包含：总评、技术亮点、薄弱项、改进建议")
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
                // 日志 Advisor 已移除（隐私红线，防止简历进日志）
//                // 开启日志，便于观察效果
//                .advisors(new MyLoggerAdvisor())
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
                // 日志 Advisor 已移除（隐私红线，防止简历进日志）
//                // 开启日志，便于观察效果
//                .advisors(new MyLoggerAdvisor())
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
                // 日志 Advisor 已移除（隐私红线，防止简历进日志）
//                // 开启日志，便于观察效果
//                .advisors(new MyLoggerAdvisor())
                .toolCallbacks(toolCallbackProvider)
                .call()
                .chatResponse();
        String content = chatResponse.getResult().getOutput().getText();
        log.info("content: {}", content);
        return content;
    }
}