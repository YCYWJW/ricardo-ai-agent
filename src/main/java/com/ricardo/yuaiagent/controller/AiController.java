package com.ricardo.yuaiagent.controller;

import com.ricardo.yuaiagent.agent.RicardoManus;
import com.ricardo.yuaiagent.app.InterviewApp;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.io.IOException;

@RestController
@RequestMapping("/ai")
@Slf4j
public class AiController {

    @Resource
    private InterviewApp interviewApp;

    @Resource
    private ToolCallback[] allTools;

    @Resource
    private ChatModel dashscopeChatModel;

    /**
     * 同步调用 AI 智能面试官应用
     *
     * @param message
     * @param chatId
     * @return
     */
    @GetMapping("/interview_app/chat/sync")
    public String doChatWithInterviewAppSync(String message, String chatId) {
        return interviewApp.doChat(message, chatId);
    }

    /**
     * SSE 流式调用 AI 智能面试官应用
     *
     * @param message
     * @param chatId
     * @return
     */
    @GetMapping(value = "/interview_app/chat/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> doChatWithInterviewAppSSE(String message, String chatId) {
        return interviewApp.doChatByStream(message, chatId);
    }

    /**
     * SSE 流式调用 AI 智能面试官应用
     *
     * @param message
     * @param chatId
     * @return
     */
    @GetMapping(value = "/interview_app/chat/server_sent_event")
    public Flux<ServerSentEvent<String>> doChatWithInterviewAppServerSentEvent(String message, String chatId) {
        return interviewApp.doChatByStream(message, chatId)
                .map(chunk -> ServerSentEvent.<String>builder()
                        .data(chunk)
                        .build());
    }

    /**
     * SSE 流式调用 AI 智能面试官应用
     *
     * @param message
     * @param chatId
     * @return
     */
    @GetMapping(value = "/interview_app/chat/sse_emitter")
    public SseEmitter doChatWithInterviewAppServerSseEmitter(String message, String chatId) {
        // 创建一个超时时间较长的 SseEmitter
        SseEmitter sseEmitter = new SseEmitter(180000L); // 3 分钟超时
        // 获取 Flux 响应式数据流并且直接通过订阅推送给 SseEmitter。
        // 必须把这个 Disposable 接住存下来：它就是后台那条大模型数据流的「总闸」。
        // 原来的写法把返回值直接丢掉了，等于这条流一旦订上就再也关不掉——
        // 用户关掉网页后，后台仍在一步步生成内容、持续计费。
        Disposable subscription = interviewApp.doChatByStream(message, chatId)
                .subscribe(chunk -> {
                    try {
                        sseEmitter.send(chunk);
                    } catch (IOException e) {
                        sseEmitter.completeWithError(e);
                    }
                }, sseEmitter::completeWithError, sseEmitter::complete);
        // 连接结束 / 超时 / 出错，三种收场都要把订阅掐掉。
        // onCompletion 在「正常跑完」和「客户端提前断开」两种情况下都会触发，
        // 所以这条日志用的是中性说法；真正能区分两者的，是 BaseAgent 那边带原因的告警日志。
        sseEmitter.onCompletion(() -> {
            subscription.dispose();
            log.info("SSE 连接结束，已取消大模型订阅");
        });
        sseEmitter.onTimeout(() -> {
            subscription.dispose();
            log.warn("SSE 连接超时，已取消大模型订阅");
        });
        sseEmitter.onError(throwable -> {
            subscription.dispose();
            log.warn("SSE 连接出错，已取消大模型订阅：{}", throwable.getMessage());
        });
        // 返回
        return sseEmitter;
    }

    /**
     * 流式调用 Manus 超级智能体
     *
     * @param message
     * @return
     */
    @GetMapping("/manus/chat")
    public SseEmitter doChatWithManus(String message) {
        RicardoManus ricardoManus = new RicardoManus(allTools, dashscopeChatModel);
        return ricardoManus.runStream(message);
    }
}