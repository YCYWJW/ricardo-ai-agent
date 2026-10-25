package com.ricardo.yuaiagent.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 启动时自检「配置里选的那个模型源」到底能不能用，不行就当场让应用起不来（Fail-Fast）。
 *
 * <h2>为什么要有这个类</h2>
 * 模型源是配置驱动的（{@code ai.provider}）。如果配置写了 {@code ollama}，但本地 Ollama 没启动，
 * 应用本身照样能正常起来——直到用户真的开始面试、说出第一句话，才会在运行时抛异常。
 * 那种失败方式是最糟的：问题在几分钟后才暴露，而且现场往往正在演示。
 * 所以这里改成启动就检查，把「配置选错了」和「服务没开」这两类问题在启动那一秒就拦下来。
 *
 * <h2>为什么用 ApplicationRunner 而不是 @PostConstruct</h2>
 * 两者都能让启动失败。用 {@code ApplicationRunner} 的原因是它跑在容器刷新之后，
 * 抛出的异常会作为顶层错误直接打出来，日志里第一眼就能看到我们写的那段中文建议；
 * 而 {@code @PostConstruct} 抛异常会被包成 BeanCreationException，得再往里翻一层才看得到原因。
 * 代价是 Tomcat 这时可能已经短暂占用了端口——对本地开发工具来说无所谓，反正进程马上就退出了。
 */
@Component
@Slf4j
public class ModelProviderStartupCheck implements ApplicationRunner {

    /**
     * 探测超时时间。
     * 本地服务正常时几毫秒就返回，给 2 秒绰绰有余；关键是必须有超时，
     * 否则万一端口被某个只收不答的程序占着，启动就会一直挂在这里。
     */
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(2);

    /** 当前选定的模型源，取值 deepseek（云端）或 ollama（本地） */
    private final String provider;

    /** 本地 Ollama 的地址，取自配置而不是写死，这样改了端口/换了机器也不用改这里 */
    private final String ollamaBaseUrl;

    public ModelProviderStartupCheck(@Value("${ai.provider:deepseek}") String provider,
                                     @Value("${spring.ai.ollama.base-url:http://localhost:11434}") String ollamaBaseUrl) {
        this.provider = provider;
        this.ollamaBaseUrl = ollamaBaseUrl;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!useLocalModel()) {
            // 云端那条路不需要额外探测：DashScope 是公网服务，
            // 就算 Key 有问题也会在真正调用时报错，没必要在这里多做一次网络请求。
            log.info("模型源自检通过：当前使用云端 DashScope（ai.provider={}）", provider);
            return;
        }
        String failure = probeOllama();
        if (failure == null) {
            log.info("模型源自检通过：当前使用本地 Ollama（{}）", ollamaBaseUrl);
            return;
        }
        String message = """
                检测到当前配置为本地 Ollama 模式，但本地 11434 端口未响应。
                请先在终端运行 'ollama serve'，或者检查你的 application.yml 配置。
                （探测地址：%s，失败原因：%s）""".formatted(ollamaBaseUrl, failure);
        // 先 log.error 再抛：抛异常保证应用起不来，打日志保证这段中文建议在人一眼能看到的位置
        log.error(message);
        throw new IllegalStateException(message);
    }

    /** 判定当前是否为本地模型模式：只有明确写了 ollama 才算，其它任何值（含拼错）都当云端处理 */
    private boolean useLocalModel() {
        return "ollama".equalsIgnoreCase(provider == null ? null : provider.trim());
    }

    /**
     * 探测本地 Ollama 是否可用。
     * <p>
     * 故意请求 {@code /api/tags}（列出本地模型的那个接口）而不是只做一次 TCP 连接：
     * TCP 连得上只说明「有东西占着这个端口」，能正常应答才说明那确实是 Ollama。
     *
     * @return 可用返回 null；不可用则返回失败原因
     */
    private String probeOllama() {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(PROBE_TIMEOUT).build()) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ollamaBaseUrl + "/api/tags"))
                    .timeout(PROBE_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 200 ? null : "HTTP " + response.statusCode();
        } catch (InterruptedException e) {
            // 恢复中断标记：吞掉中断信号会让上层线程池无法感知关闭请求
            Thread.currentThread().interrupt();
            return "探测过程被中断";
        } catch (Exception e) {
            // 连不上、超时、地址写错等等，都归到这里，只取类型 + 原因，堆栈留给外层打
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }
}