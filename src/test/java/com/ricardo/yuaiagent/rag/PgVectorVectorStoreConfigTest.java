package com.ricardo.yuaiagent.rag;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

/**
 * PgVector 配置测试。当前 PgVectorVectorStoreConfig 类整体被注释（未标注 @Configuration），
 * 容器里不存在 pgVectorVectorStore 这个 bean，因此本测试直接 @Disabled。
 * 等后续真正启用 PgVector 时，再连同配置一起解封并做专项测试。
 */
@SpringBootTest
@Disabled("PgVectorVectorStoreConfig 当前未启用（bean 不存在），等启用后再解封本测试")
class PgVectorVectorStoreConfigTest {

    @Resource
    private VectorStore pgVectorVectorStore;

    @Test
    void pgVectorVectorStore() {
        List<Document> documents = List.of(
                new Document("学编程有什么用？做项目啊", Map.of("meta1", "meta1")),
                new Document("原创项目教程"),
                new Document("李嘉图这小伙子比较帅气", Map.of("meta2", "meta2")));
        // 添加文档
        pgVectorVectorStore.add(documents);
        // 相似度查询
        List<Document> results = pgVectorVectorStore.similaritySearch(SearchRequest.builder().query("怎么学编程啊").topK(3).build());
        Assertions.assertNotNull(results);
    }
}