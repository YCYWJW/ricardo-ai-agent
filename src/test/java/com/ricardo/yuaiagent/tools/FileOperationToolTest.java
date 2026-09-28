package com.ricardo.yuaiagent.tools;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * 文件操作工具单元测试：直接 new 对象读写本地临时文件，不启动 Spring、不调用 AI，因此不烧 token。
 */
class FileOperationToolTest {

    @Test
    void readFile() {
        FileOperationTool fileOperationTool = new FileOperationTool();
        String fileName = "test-file.txt";
        String result = fileOperationTool.readFile(fileName);
        Assertions.assertNotNull(result);
    }

    @Test
    void writeFile() {
        FileOperationTool fileOperationTool = new FileOperationTool();
        String fileName = "test-file.txt";
        String content = "这是一个编程学习交流社区";
        String result = fileOperationTool.writeFile(fileName, content);
        Assertions.assertNotNull(result);
    }
}
