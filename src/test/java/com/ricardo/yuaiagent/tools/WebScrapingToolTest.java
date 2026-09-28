package com.ricardo.yuaiagent.tools;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * 网页抓取工具测试。scrapeWebPage 会对 example.com 发起真实外网请求，
 * 为让 CI 不依赖网络，暂时 @Disabled。等需要时改为本地 mock HTTP 后再解封。
 */
@Disabled("会真实访问外网，CI 无网环境会失败，等改为 mock HTTP 后再解封")
class WebScrapingToolTest {

    @Test
    void scrapeWebPage() {
        WebScrapingTool webScrapingTool = new WebScrapingTool();
        String url = "https://example.com";
        String result = webScrapingTool.scrapeWebPage(url);
        Assertions.assertNotNull(result);
    }
}
