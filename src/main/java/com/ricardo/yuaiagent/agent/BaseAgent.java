package com.ricardo.yuaiagent.agent;

import cn.hutool.core.util.StrUtil;
import com.ricardo.yuaiagent.agent.model.AgentState;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 抽象基础代理类，用于管理代理状态和执行流程。
 * <p>
 * 提供状态转换、内存管理和基于步骤的执行循环的基础功能。
 * 子类必须实现step方法。
 *
 * <p>关于 Lombok：这里刻意没有用 {@code @Data}。
 * {@code @Data} 会给类的每一个字段都生成 public 的 getter/setter，
 * 而这是个「抽象基类 + 一大堆可变状态」的场合，等于把内部状态全部对外开放——
 * 下面那个控制后台任务生死的 stopped 字段就是被它开出后门的（外部随手 setStopped(false)
 * 就能让取消失效）。所以改成逐个显式声明，需要开放的开放，不需要的一个都不给。
 */
@Getter
@Setter
@ToString
@EqualsAndHashCode(exclude = "stopped")   // 流控开关不参与相等性判断，否则两个状态相同的 agent 会被判为不等
@Slf4j
public abstract class BaseAgent {

    // 核心属性
    private String name;

    // 提示词
    private String systemPrompt;
    private String nextStepPrompt;

    // 代理状态
    private AgentState state = AgentState.IDLE;

    // 执行步骤控制
    private int currentStep = 0;
    private int maxSteps = 10;

    // LLM 大模型
    private ChatClient chatClient;

    // Memory 记忆（需要自主维护会话上下文）
    private List<Message> messageList = new ArrayList<>();

    /**
     * 后台流式任务的执行器。
     * <p>
     * 用虚拟线程（Java 21 正式版特性，创建成本极低），而不是把任务丢给
     * CompletableFuture 默认的 ForkJoinPool.commonPool —— 后者是给 CPU 密集型任务准备的公共池，
     * 拿它去跑「等大模型返回」这种长时间阻塞的网络 IO，会把公共池的线程全占住，拖累整个 JVM。
     * <p>
     * 另一个更关键的原因：只有拿到真正的 Future 才能取消。
     * ExecutorService.submit 返回的是 FutureTask，cancel(true) 会真的打断执行线程；
     * 而 CompletableFuture.cancel(true) 那个 boolean 参数是无效的——它只标记成「已取消」，
     * 根本不会去中断正在跑的线程。
     * <p>
     * 该执行器是全局共享的，虚拟线程用完即弃，不需要也不应该关闭。
     */
    private static final ExecutorService STREAM_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * 「该停下了」的开关，用来防止用户关掉网页后，后台还在一步一步调大模型烧钱。
     * <p>
     * 为什么必须加 volatile：置位它的是 Tomcat 的 SSE 回调线程，读它的是后台执行线程。
     * 不加 volatile，后台线程可能一直读到缓存的旧值 false，循环就永远停不下来。
     * <p>
     * 纯内部状态：不生成 getter/setter，外部既读不到也改不了，
     * 只能由本类自己的循环和 cancel 方法操作，避免出现「外部把开关改回去」这种后门。
     */
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private volatile boolean stopped = false;

    /**
     * 运行代理
     *
     * @param userPrompt 用户提示词
     * @return 执行结果
     */
    public String run(String userPrompt) {
        // 1、基础校验
        if (this.state != AgentState.IDLE) {
            throw new RuntimeException("Cannot run agent from state: " + this.state);
        }
        if (StrUtil.isBlank(userPrompt)) {
            throw new RuntimeException("Cannot run agent with empty user prompt");
        }
        // 2、执行，更改状态
        this.state = AgentState.RUNNING;
        // 记录消息上下文
        messageList.add(new UserMessage(userPrompt));
        // 保存结果列表
        List<String> results = new ArrayList<>();
        try {
            // 执行循环
            for (int i = 0; i < maxSteps && state != AgentState.FINISHED; i++) {
                int stepNumber = i + 1;
                currentStep = stepNumber;
                log.info("Executing step {}/{}", stepNumber, maxSteps);
                // 单步执行
                String stepResult = step();
                String result = "Step " + stepNumber + ": " + stepResult;
                results.add(result);
            }
            // 检查是否超出步骤限制
            if (currentStep >= maxSteps) {
                state = AgentState.FINISHED;
                results.add("Terminated: Reached max steps (" + maxSteps + ")");
            }
            return String.join("\n", results);
        } catch (Exception e) {
            state = AgentState.ERROR;
            log.error("error executing agent", e);
            return "执行错误" + e.getMessage();
        } finally {
            // 3、清理资源
            this.cleanup();
        }
    }

    /**
     * 运行代理（流式输出）
     *
     * @param userPrompt 用户提示词
     * @return 执行结果
     */
    public SseEmitter runStream(String userPrompt) {
        // 创建一个超时时间较长的 SseEmitter
        SseEmitter sseEmitter = new SseEmitter(300000L); // 5 分钟超时
        // 每次运行都把开关复位，避免同一实例被复用时残留上一次的取消状态
        this.stopped = false;
        // 使用线程异步处理，避免阻塞主线程。
        // 这里必须把 Future 留下来：客户端断开时要靠它把后台线程打断，否则大模型会一直生成下去。
        Future<?> future = STREAM_EXECUTOR.submit(() -> {
            // 1、基础校验
            try {
                if (this.state != AgentState.IDLE) {
                    sseEmitter.send("错误：无法从状态运行代理：" + this.state);
                    completeSelf(sseEmitter, null);
                    return;
                }
                if (StrUtil.isBlank(userPrompt)) {
                    sseEmitter.send("错误：不能使用空提示词运行代理");
                    completeSelf(sseEmitter, null);
                    return;
                }
            } catch (Exception e) {
                // 这里不用再写 return：completeSelf 会把 stopped 立起来，
                // 下面循环开头的 !stopped 判断自然就不会往下跑了（原来的写法会继续把 10 步跑完）
                completeSelf(sseEmitter, e);
            }
            // 2、执行，更改状态
            this.state = AgentState.RUNNING;
            // 记录消息上下文
            messageList.add(new UserMessage(userPrompt));
            // 保存结果列表
            List<String> results = new ArrayList<>();
            try {
                // 执行循环。多出来的 !stopped 才是真正省钱的那道闸：
                // 客户端一断，下一步就不会再执行，也就不会再调大模型
                for (int i = 0; i < maxSteps && state != AgentState.FINISHED && !stopped; i++) {
                    int stepNumber = i + 1;
                    currentStep = stepNumber;
                    log.info("Executing step {}/{}", stepNumber, maxSteps);
                    // 单步执行
                    String stepResult = step();
                    String result = "Step " + stepNumber + ": " + stepResult;
                    results.add(result);
                    // 这一步跑的过程中客户端可能已经走了，那就别再往一条断掉的连接上写，
                    // 否则会抛异常被下面的 catch 接住，刷出一堆其实什么都没坏的错误日志
                    if (stopped) {
                        break;
                    }
                    // 输出当前每一步的结果到 SSE
                    sseEmitter.send(result);
                }
                // 检查是否超出步骤限制（被取消时不用再发这句提示，客户端已经不在了）
                if (!stopped && currentStep >= maxSteps) {
                    state = AgentState.FINISHED;
                    results.add("Terminated: Reached max steps (" + maxSteps + ")");
                    sseEmitter.send("执行结束：达到最大步骤（" + maxSteps + "）");
                }
                // 正常完成
                completeSelf(sseEmitter, null);
            } catch (Exception e) {
                state = AgentState.ERROR;
                log.error("error executing agent", e);
                try {
                    sseEmitter.send("执行错误：" + e.getMessage());
                    completeSelf(sseEmitter, null);
                } catch (IOException ex) {
                    completeSelf(sseEmitter, ex);
                }
            } finally {
                // 3、清理资源
                this.cleanup();
            }
        });

        // 设置超时回调
        sseEmitter.onTimeout(() -> {
            this.state = AgentState.ERROR;
            this.cleanup();
            cancel(future, "SSE 连接超时");
            log.warn("SSE connection timeout");
        });
        // 设置完成回调（正常跑完 和 客户端提前断开 两种情况都会触发它）
        sseEmitter.onCompletion(() -> {
            if (this.state == AgentState.RUNNING) {
                this.state = AgentState.FINISHED;
            }
            this.cleanup();
            cancel(future, "SSE 连接已结束");
            log.info("SSE connection completed");
        });
        // 设置错误回调：原来缺这一个，连接一出错后台就会照样把 maxSteps 步跑满
        sseEmitter.onError(throwable -> {
            this.state = AgentState.ERROR;
            cancel(future, "SSE 连接出错：" + throwable.getMessage());
        });
        return sseEmitter;
    }

    /**
     * 由后台任务自己收尾（正常跑完 或 运行出错 都走这里）。
     * <p>
     * 顺序很关键：必须先把 stopped 立起来，再关 SSE。
     * complete() / completeWithError() 会立刻触发上面注册的 onCompletion / onError 回调，
     * 那时若 stopped 还是 false，回调就会把这次「我自己正常结束」误判成「客户端跑了」，
     * 结果既打出一条假的告警日志，又会去打断一个正在收尾的线程。
     *
     * @param error 为 null 表示正常结束，否则按出错结束
     */
    private void completeSelf(SseEmitter sseEmitter, Throwable error) {
        // 先立开关，再关连接 —— 这两行的顺序不能调换
        this.stopped = true;
        if (error == null) {
            sseEmitter.complete();
        } else {
            sseEmitter.completeWithError(error);
        }
    }

    /**
     * 停掉后台任务：立起开关并打断线程。
     * <p>
     * 幂等，重复调用只会生效一次，所以「正常结束」和「客户端断开」两条路径都调它也没问题。
     *
     * @param reason 记日志用的原因，方便线上排查这次到底是不是客户端跑了
     */
    private void cancel(Future<?> future, String reason) {
        // 已经停了就直接返回：正常跑完时 onCompletion 也会走到这里，
        // 那次是任务自己结束的，不该再打断它，更不该打一条「客户端断开」的假日志
        if (stopped) {
            return;
        }
        stopped = true;
        // cancel(true) 的返回值含义是「这次调用是否真的取消了任务」：
        // 任务早跑完了返回 false，只有还在跑才返回 true —— 正好用来决定该不该记这条日志
        if (future.cancel(true)) {
            log.warn("{}，已中断后台任务，后续步骤不再调用大模型", reason);
        }
    }

    /**
     * 定义单个步骤
     *
     * @return
     */
    public abstract String step();

    /**
     * 清理资源
     */
    protected void cleanup() {
        // 子类可以重写此方法来清理资源
    }
}
