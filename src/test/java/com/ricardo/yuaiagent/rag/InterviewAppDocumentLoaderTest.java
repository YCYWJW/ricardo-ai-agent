package com.ricardo.yuaiagent.rag;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 文档加载器单元测试：用 PathMatchingResourcePatternResolver 直接读取 classpath 下的 Markdown，
 * 不启动 Spring 上下文、不调用任何 AI，因此不会烧 token。
 */
class InterviewAppDocumentLoaderTest {

    @Test
    void loadMarkdowns() {
        InterviewAppDocumentLoader loader =
                new InterviewAppDocumentLoader(new PathMatchingResourcePatternResolver());
        List<Document> documents = loader.loadMarkdowns();
        assertNotNull(documents);
        assertFalse(documents.isEmpty(), "应能加载到至少一篇知识库文档");
    }
}