package com.ricardo.yuaiagent.tools;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 网页搜索工具测试。searchWeb 会对 searchapi.io 发起真实外网请求（需要真实 key + 网络），
 * 在 CI / 无网环境必然失败，因此 @Disabled。等需要时改为本地 mock HTTP 后再解封。
 */
@SpringBootTest
@Disabled("会真实调用外部搜索 API，CI 无 key 无网环境必挂，等改为 mock HTTP 后再解封")
class WebSearchToolTest {

    @Value("${search-api.api-key}")
    private String searchApiKey;

    @Test
    void searchWeb() {
        WebSearchTool webSearchTool = new WebSearchTool(searchApiKey);
        String query = "李嘉图 编程学习";
        String result = webSearchTool.searchWeb(query);
        Assertions.assertNotNull(result);
    }
}
