package com.ricardo.yuaiagent.rag;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Disabled("阶段一暂时跳过，后续做专项Mock测试")
class InterviewAppDocumentLoaderTest {

    @Resource
    private InterviewAppDocumentLoader interviewAppDocumentLoader;

    @Test
    void loadMarkdowns() {
        interviewAppDocumentLoader.loadMarkdowns();
    }
}