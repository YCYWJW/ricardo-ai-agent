package com.ricardo.yuaiagent.app;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

@SpringBootTest
@Disabled("阶段一暂时跳过，后续做专项Mock测试")
class InterviewAppTest {

    @Resource
    private InterviewApp interviewApp;

    @Test
    void testChat() {
        String chatId = UUID.randomUUID().toString();
        // 第一轮：候选人自我介绍
        String message = "你好，我是一名有 3 年经验的 Java 后端开发工程师张三";
        String answer = interviewApp.doChat(message, chatId);
        Assertions.assertNotNull(answer);
        // 第二轮：抛出一个项目经历，请求模拟面试
        message = "我想请你针对我参与的电商订单系统项目，做一场 Java 后端模拟面试，帮我深挖项目细节";
        answer = interviewApp.doChat(message, chatId);
        Assertions.assertNotNull(answer);
        // 第三轮：验证多轮对话记忆
        message = "我刚才说我叫什么、做过什么项目？帮我回忆一下，看看你有没有记住";
        answer = interviewApp.doChat(message, chatId);
        Assertions.assertNotNull(answer);
    }

    @Test
    void doChatWithReport() {
        String chatId = UUID.randomUUID().toString();
        String message = "你好，我是候选人张三，请你基于我的简历做一次 Java 后端模拟面试，最后给出面试评估报告";
        InterviewApp.InterviewReport report = interviewApp.doChatWithReport(message, chatId);
        Assertions.assertNotNull(report);
        // 校验结构化报告的四个字段：总评、技术亮点、薄弱项、改进建议
        Assertions.assertNotNull(report.overview());
        Assertions.assertNotNull(report.strengths());
        Assertions.assertNotNull(report.weaknesses());
        Assertions.assertNotNull(report.suggestions());
    }

    @Test
    void doChatWithRag() {
        String chatId = UUID.randomUUID().toString();
        // RAG 知识库已换为 Java 面试题库文档
        String message = "面试中常问的 Java 后端基础知识有哪些？";
        String answer = interviewApp.doChatWithRag(message, chatId);
        Assertions.assertNotNull(answer);
    }

    @Test
    void doChatWithTools() {
        // 测试联网搜索：面试题
        testMessage("帮我联网搜索一下 Java 后端面试中 Redis 的高频面试题？");

        // 测试网页抓取：面试真题
        testMessage("抓取一个网页，看看最近的 Java 后端面试真题");

        // 测试资源下载：图片下载
        testMessage("直接下载一张 Java 技术相关的图片为文件");

        // 测试终端操作：执行代码
        testMessage("执行 Python3 脚本来生成一份面试题分析报告");

        // 测试文件操作：保存面试档案
        testMessage("保存我的面试准备档案为文件");

        // 测试 PDF 生成
        testMessage("生成一份'Java 后端面试准备计划'PDF，包含复习清单、项目梳理和模拟面试安排");
    }

    private void testMessage(String message) {
        String chatId = UUID.randomUUID().toString();
        String answer = interviewApp.doChatWithTools(message, chatId);
        Assertions.assertNotNull(answer);
    }

    @Test
    void doChatWithMcp() {
        String chatId = UUID.randomUUID().toString();
        // 测试地图 MCP
//        String message = "请帮我找到 5 公里内适合面试的咖啡厅";
//        String answer =  interviewApp.doChatWithMcp(message, chatId);
//        Assertions.assertNotNull(answer);
        // 测试图片搜索 MCP
        String message = "帮我搜索一些程序员面试相关的图片";
        String answer =  interviewApp.doChatWithMcp(message, chatId);
        Assertions.assertNotNull(answer);
    }
}