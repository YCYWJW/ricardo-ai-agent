package com.ricardo.yuaiagent.agent;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ricardo.yuaiagent.agent.model.AgentState;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

/**
 * 流式断连测试：验证「用户关掉网页后，后台 Agent 会停下，不再继续调大模型烧钱」。
 *
 * <h2>为什么这个测试是零成本的</h2>
 * {@link FakeAgent#step()} 里只有 {@code Thread.sleep} 和一个计数器，
 * 完全不碰网络、不碰大模型，所以跑多少次都不会产生 API 费用。
 *
 * <h2>「客户端断开」到底是怎么被感知的（这是本文件的核心）</h2>
 * {@code SseEmitter.onCompletion} 注册的回调，在真机上的完整链路是：
 * <pre>
 * Servlet 容器发现连接断了
 *   → StandardServletAsyncWebRequest.onComplete(AsyncEvent)   ← 它自己就是一个 AsyncListener
 *     → completionHandlers.run()                              ← WebAsyncManager 注册进去的
 *       → DeferredResultInterceptorChain.triggerAfterCompletion
 *         → DeferredResult$LifecycleInterceptor.afterCompletion
 *           → DeferredResult.completionCallback.run()
 *             → ResponseBodyEmitter 的回调
 *               → BaseAgent 里我们写的 onCompletion
 *                 → cancel(future, "SSE 连接已结束")
 * </pre>
 * 也就是说，<b>回调能不能响，取决于「容器有没有按那个按钮」，而不取决于我们代码里调没调 complete()</b>。
 * <p>
 * 这里踩过一个很深的坑，写下来免得以后有人重复：一开始这个测试是先
 * {@code emitter.complete()} 再 {@code MockMvc.perform(asyncDispatch(...))}，
 * 看着像是模拟了断连，实际上 <b>AsyncListener 一次都没被触发</b>——
 * {@code asyncDispatch} 只是把请求重新派发一遍，它不做这件事。
 * 结果后台循环是被 {@code sseEmitter.send()} 抛出的
 * 「ResponseBodyEmitter has already completed」异常顺带打断的，
 * 测试因此「绿了，却什么都没验证到」（日志里那条 ERROR 堆栈就是铁证）。
 * <p>
 * 正确的做法是：{@code spring-test} 的 {@code MockAsyncContext} 提供了 {@code getListeners()}，
 * 把「容器按的那个按钮」交到了测试手里。测试替容器按一下 {@code onComplete}，
 * <b>后面每一步都是 Spring 的生产代码在跑，没有一处是模拟的</b>。
 * 而且不需要（也不应该）再去调 {@code emitter.complete()}：真机上客户端跑了，
 * 业务代码根本不会去关自己的连接，是容器反过来通知应用的。
 *
 * <h2>FakeAgent.step() 为什么故意吞掉中断信号</h2>
 * 真实的大模型 HTTP 调用被 interrupt 时未必真的会停。所以这里模拟最坏情况：
 * 线程已经被打断，这一步也照样睡满。这样测出来的就不是「靠中断侥幸生效」，
 * 而是 {@code stopped} 标记位那道闸——那才是真正保证「后续步骤一次都不再执行」的东西。
 */
class BaseAgentStreamCancelTest {

    /** 单个步骤的耗时。取大一点，好让测试有机会在「跑完之前」把连接掐掉 */
    private static final long STEP_MILLIS = 150L;

    /** 假 Agent：只为测试而存在的一步步「假装在干活」，不碰大模型、不产生任何费用 */
    static class FakeAgent extends BaseAgent {

        /** 被调用了几次 step —— 这就是「烧了几次钱」的直接度量 */
        final AtomicInteger stepCount = new AtomicInteger();

        FakeAgent() {
            setName("fake-agent-for-test");
            setMaxSteps(10);
        }

        @Override
        public String step() {
            stepCount.incrementAndGet();
            // 不理会中断，把这一步睡完（原因见类注释）
            long deadline = System.currentTimeMillis() + STEP_MILLIS;
            while (System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(20L);
                } catch (InterruptedException ignored) {
                    // 故意不理会：模拟「被 interrupt 了也停不下来的阻塞调用」
                }
            }
            return "fake-step";
        }
    }

    /** 测试专用控制器：只负责走一遍真实的 MVC 派发，好让 SseEmitter 被「接线」 */
    @RestController
    static class FakeStreamController {

        private final BaseAgent agent;

        FakeStreamController(BaseAgent agent) {
            this.agent = agent;
        }

        @GetMapping("/test/agent/stream")
        public SseEmitter stream() {
            return agent.runStream("测试提问");
        }
    }

    @Test
    @DisplayName("客户端断开后：后台任务应停下，不应再跑完剩下的步骤")
    void shouldStopBackgroundTaskAfterClientDisconnects() throws Exception {
        FakeAgent agent = new FakeAgent();
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new FakeStreamController(agent)).build();

        // 发起请求：走真实 MVC 派发，emitter 会被「接线」
        MvcResult mvcResult = startStream(mockMvc);
        assertTrue(waitUntil(() -> agent.stepCount.get() >= 1, 2000L),
                "后台任务应当已经开始执行第一步");

        // 容器通知：客户端把网页关了
        notifyClientDisconnected(mvcResult);

        // 先给正在飞行中的那一步一点时间落地（取消最多只能拦住「下一步」，拦不住「这一步」）
        Thread.sleep(400L);
        int stepsAtCancel = agent.stepCount.get();

        // 再等一段足够跑好几步的时间：如果开关没生效，这期间一定会又涨好几步
        Thread.sleep(600L);
        int stepsLater = agent.stepCount.get();

        assertEquals(stepsAtCancel, stepsLater,
                "断连之后不应该再有新的 step 被执行（断连前 " + stepsAtCancel + " 步，之后 " + stepsLater + " 步）");
        assertTrue(stepsLater < agent.getMaxSteps(),
                "应当在跑满 maxSteps(10) 之前就停下，实际跑了 " + stepsLater + " 步");
    }

    @Test
    @DisplayName("客户端断开时：应打出带原因的「已中断」告警日志")
    void shouldLogCancelReasonWhenClientDisconnects() throws Exception {
        FakeAgent agent = new FakeAgent();
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new FakeStreamController(agent)).build();

        ListAppender<ILoggingEvent> logs = attachLogCaptor();
        try {
            MvcResult mvcResult = startStream(mockMvc);
            assertTrue(waitUntil(() -> agent.stepCount.get() >= 1, 2000L), "后台任务应当已经开始执行");

            notifyClientDisconnected(mvcResult);

            // 回调是在触发异步事件的那个线程上跑的，但保险起见还是等一小会儿
            assertTrue(waitUntil(() -> cancelledLogCount(logs) >= 1, 2000L),
                    "应当有一条「已中断后台任务」的告警日志，实际没等到——"
                            + "没有这条，就说明 onCompletion 那条链路根本没走通，测试是空跑的");
            assertTrue(logs.list.stream().anyMatch(e -> e.getFormattedMessage().contains("SSE 连接已结束")),
                    "告警日志里应当带上取消原因，方便线上排查到底是不是客户端跑了");
        } finally {
            detachLogCaptor(logs);
        }
    }

    @Test
    @DisplayName("连接出错时：同样应停住后台任务，并打出带原因的告警")
    void shouldStopBackgroundTaskWhenConnectionErrors() throws Exception {
        FakeAgent agent = new FakeAgent();
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new FakeStreamController(agent)).build();

        ListAppender<ILoggingEvent> logs = attachLogCaptor();
        try {
            MvcResult mvcResult = startStream(mockMvc);
            assertTrue(waitUntil(() -> agent.stepCount.get() >= 1, 2000L), "后台任务应当已经开始执行");

            // 容器报告连接出错（真机上典型的是客户端硬断导致的 Broken pipe）。
            // 顺带一个实测细节：Spring 的 DisconnectedClientHelper 内置了
            // {"broken pipe", "connection reset by peer"} 这类断连签名，认出来之后会把原始异常
            // 换成一句中性的「Servlet container error notification for disconnected client」，
            // 免得日志看起来像服务端自己出了故障。所以下面断言的是我们自己加的前缀，
            // 而不是那句会随 Spring 版本变化的内置文案。
            notifyConnectionError(mvcResult, new IOException("Broken pipe"));

            assertTrue(waitUntil(() -> cancelledLogCount(logs) >= 1, 2000L),
                    "连接出错也应当停掉后台任务，否则会照样把 maxSteps 步跑满");
            assertTrue(logs.list.stream().anyMatch(e -> e.getFormattedMessage().contains("SSE 连接出错")),
                    "告警日志里应当带上是哪次出错，实际没等到");

            Thread.sleep(400L);
            int stepsAtCancel = agent.stepCount.get();
            Thread.sleep(600L);
            assertEquals(stepsAtCancel, agent.stepCount.get(),
                    "出错之后不应该再有新的 step 被执行");
            assertTrue(stepsAtCancel < agent.getMaxSteps(),
                    "应当在跑满 maxSteps(10) 之前就停下，实际跑了 " + stepsAtCancel + " 步");
        } finally {
            detachLogCaptor(logs);
        }
    }

    @Test
    @DisplayName("正常跑完时：不应被误判成「客户端断开」而打出假的取消告警")
    void shouldNotLogCancelWhenFinishedNormally() throws Exception {
        FakeAgent agent = new FakeAgent();
        agent.setMaxSteps(3);   // 正常跑 3 步就结束
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new FakeStreamController(agent)).build();

        ListAppender<ILoggingEvent> logs = attachLogCaptor();
        try {
            startStream(mockMvc);

            assertTrue(waitUntil(() -> agent.getState() == AgentState.FINISHED, 4000L),
                    "Agent 应当正常跑完并进入 FINISHED 状态");
            // completeSelf 是先立开关再关连接，给它一点时间跑完收尾
            Thread.sleep(300L);

            assertEquals(3, agent.stepCount.get(), "正常跑完应当正好执行 maxSteps(3) 步");
            // 这条是「先立 stopped 开关、再关连接」那个顺序的回归测试：
            // 顺序若写反，complete() 触发的 onCompletion 会去 cancel 一个正在收尾的自己，
            // 从而打出一条假的「客户端断开」告警，这条断言就会红
            assertEquals(0, cancelledLogCount(logs),
                    "正常结束属于「任务自己跑完」，不该被打上「已中断后台任务」的假告警");
        } finally {
            detachLogCaptor(logs);
        }
    }

    // ------------------------------------------------------------------
    // 测试辅助
    // ------------------------------------------------------------------

    /** 发起一次流式请求，并确认真的进了 Servlet 异步模式（否则说明 emitter 压根没被接线） */
    private static MvcResult startStream(MockMvc mockMvc) throws Exception {
        return mockMvc.perform(get("/test/agent/stream"))
                .andExpect(request().asyncStarted())
                .andReturn();
    }

    /**
     * 替 Servlet 容器通知「连接断了」——真机上这一步由容器在检测到客户端断开后执行，
     * 测试环境里没有真容器，就由这里按下同一个按钮。
     */
    private static void notifyClientDisconnected(MvcResult mvcResult) throws Exception {
        fireAsyncEvent(mvcResult, null, AsyncListener::onComplete);
    }

    /** 同上，但通知的是「连接出错」 */
    private static void notifyConnectionError(MvcResult mvcResult, Throwable error) throws Exception {
        fireAsyncEvent(mvcResult, error, AsyncListener::onError);
    }

    /**
     * 「怎么把异步事件递出去」这一步的抽象（onComplete 还是 onError）。
     * <p>
     * 之所以不直接用 JDK 自带的 {@code BiConsumer}：{@link AsyncListener} 的
     * {@code onComplete} / {@code onError} 都声明了 {@code throws IOException}，
     * 而 {@code BiConsumer} 不允许受检异常，方法引用套不进去。所以自己写一个允许抛异常的。
     */
    @FunctionalInterface
    private interface AsyncEventDelivery {
        void deliver(AsyncListener listener, AsyncEvent event) throws Exception;
    }

    /**
     * 把异步事件派发给容器注册的那些 {@link AsyncListener}。
     * <p>
     * 派发之后跑的 <b>全是 Spring 的生产代码</b>：监听器 → WebAsyncManager 的收尾处理器
     * → DeferredResult 的 completionCallback → SseEmitter 的回调 → BaseAgent 里的取消逻辑。
     * <p>
     * 中间那两个断言很关键：万一哪天 MockMvc 的行为变了（异步没起来、或者容器没注册监听器），
     * 这个测试必须「大声失败」，而不是安安静静地空跑过去。
     */
    private static void fireAsyncEvent(MvcResult mvcResult, Throwable error,
                                       AsyncEventDelivery delivery) throws Exception {
        MockAsyncContext asyncContext = (MockAsyncContext) mvcResult.getRequest().getAsyncContext();
        assertNotNull(asyncContext, "请求应当处于 Servlet 异步模式，否则 MVC 根本没给 SseEmitter 接线");
        assertFalse(asyncContext.getListeners().isEmpty(),
                "容器应当已经在异步上下文上注册过 AsyncListener，否则这次断连通知发不出去");
        AsyncEvent event = (error == null) ? new AsyncEvent(asyncContext) : new AsyncEvent(asyncContext, error);
        for (AsyncListener listener : asyncContext.getListeners()) {
            delivery.deliver(listener, event);
        }
    }

    /** 轮询等待条件成立，超时返回 false；比写死 sleep 更稳，也不会拖慢测试 */
    private static boolean waitUntil(BooleanSupplier condition, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(20L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }

    /** 数一数日志里出现了几条「已中断后台任务」 */
    private static long cancelledLogCount(ListAppender<ILoggingEvent> logs) {
        return logs.list.stream()
                .filter(e -> e.getFormattedMessage().contains("已中断后台任务"))
                .count();
    }

    /** 把 BaseAgent 的日志挂到一个内存收集器上，供断言使用 */
    private static ListAppender<ILoggingEvent> attachLogCaptor() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(BaseAgent.class)).addAppender(appender);
        return appender;
    }

    private static void detachLogCaptor(ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(BaseAgent.class)).detachAppender(appender);
    }
}
