package com.ricardo.yuaiagent.config;

import io.github.cdimascio.dotenv.Dotenv;
import io.github.cdimascio.dotenv.DotenvEntry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;

import java.util.Set;

/**
 * 环境准备阶段（Spring 启动的最早环节之一）加载项目根目录的 .env 文件，
 * 把其中的变量（如 DASHSCOPE_API_KEY）通过 System.setProperty 注入为系统属性，
 * 供 application.yml 里 ${...} 占位符在后续解析时取到。
 *
 * 注入时机：EnvironmentPostProcessor 在「Environment 构建完成、
 * ApplicationContext 刷新（Bean 创建、@Value / @ConfigurationProperties 绑定）之前」执行，
 * 因此 System.setProperty 一定先于占位符解析完成，不会出现 Could not resolve placeholder。
 */
@Slf4j
public class DotenvConfig implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        // 先加载 .env：ignoreIfMissing() 让文件缺失时不抛异常，仅在内存里得到空结果，适合 CI 等无 .env 场景
        Dotenv dotenv;
        try {
            dotenv = Dotenv.configure().ignoreIfMissing().load();
        } catch (Exception e) {
            // .env 存在但解析失败（如格式损坏）时也不让应用崩溃，只告警并回退到系统环境变量
            log.warn(".env 加载失败（{}），将使用系统环境变量。", e.getMessage());
            return;
        }

        // 注意：dotenv.entries() 会把系统环境变量也合并进来、永远不为空，
        // 所以必须用 DECLARED_IN_ENV_FILE 只取「.env 文件里声明」的变量，才能正确判断文件是否缺失。
        Set<DotenvEntry> declaredInFile = dotenv.entries(Dotenv.Filter.DECLARED_IN_ENV_FILE);

        // .env 缺失时（如 CI 环境），直接跳过注入，让后续占位符从系统环境变量取值
        if (declaredInFile.isEmpty()) {
            log.warn(".env 缺失，将使用系统环境变量。");
            return;
        }

        // 只把 .env 文件里声明的变量逐个注入为系统属性（不硬编码任何具体密钥）
        declaredInFile.forEach(entry -> {
            System.setProperty(entry.getKey(), entry.getValue());
            // 只打印变量名和长度用于验证注入成功，绝不打印真实密钥内容
            log.info("已从 .env 注入系统属性：{}（长度={}）", entry.getKey(),
                    entry.getValue() == null ? 0 : entry.getValue().length());
        });

        // 关键变量就位校验：给出清晰的失败提示（同样不打印值）
        String apiKey = dotenv.get("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            log.error(".env 中未找到 DASHSCOPE_API_KEY，请检查项目根目录的 .env 文件。");
        } else {
            log.info("DASHSCOPE_API_KEY 已就位（长度={}），等待 application.yml 占位符解析。", apiKey.length());
        }
    }
}