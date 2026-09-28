package com.ricardo.yuaiagent.tools;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 资源下载工具测试。downloadResource 会对 example.com 发起真实外网请求，
 * 为让 CI 不依赖网络，暂时 @Disabled。等需要时改为本地 mock HTTP 后再解封。
 */
@Disabled("会真实访问外网，CI 无网环境会失败，等改为 mock HTTP 后再解封")
public class ResourceDownloadToolTest {

    @Test
    public void testDownloadResource() {
        ResourceDownloadTool tool = new ResourceDownloadTool();
        String url = "https://example.com/logo.png";
        String fileName = "logo.png";
        String result = tool.downloadResource(url, fileName);
        assertNotNull(result);
    }
}