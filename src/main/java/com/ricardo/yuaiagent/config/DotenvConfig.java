package com.ricardo.yuaiagent.config;

import io.github.cdimascio.dotenv.Dotenv;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;

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
        Dotenv dotenv = Dotenv.configure().load();

        // 把 .env 里的所有变量逐个注入为系统属性（不硬编码任何具体密钥）
        dotenv.entries().forEach(entry -> {
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