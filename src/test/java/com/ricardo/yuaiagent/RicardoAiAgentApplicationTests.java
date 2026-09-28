package com.ricardo.yuaiagent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
class RicardoAiAgentApplicationTests {

    // 替换向量库 bean，跳过 Spring 启动时的向量化（35 次关键词 + 35 次 embedding），防止启动时烧 token
    @MockitoBean(name = "interviewAppVectorStore")
    private VectorStore interviewAppVectorStore;

    @Test
    void contextLoads() {
    }

}