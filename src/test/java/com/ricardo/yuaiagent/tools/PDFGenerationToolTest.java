package com.ricardo.yuaiagent.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PDFGenerationToolTest {

    @Test
    void generatePDF() {
        PDFGenerationTool tool = new PDFGenerationTool();
        String fileName = "project.pdf";
        String content = "原创项目";
        String result = tool.generatePDF(fileName, content);
        assertNotNull(result);
    }
}