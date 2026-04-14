package com.ragqa.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 文本分块服务
 * 
 * 作用：将长文档切分为较小的文本块（chunks）
 * 
 * 为什么需要分块：
 * 1. LLM有上下文长度限制，不能一次处理整篇文档
 * 2. 更小的切片便于精确检索相关段落
 * 3. 重叠切分可以避免关键信息被切断
 * 
 * 支持的分块策略：
 * 1. fixed（固定大小）：按固定字符数切分，适合结构化文档
 * 2. paragraph（按段落）：按段落切分，适合非结构化文档
 */
@Service
public class TextSplitter {

    /** 分块策略，可选值: fixed, paragraph */
    @Value("${chunk.strategy:fixed}")
    private String chunkStrategy;

    /** 每个切片的最大字符数 */
    @Value("${chunk.size:500}")
    private int CHUNK_SIZE;

    /** 相邻切片之间的重叠字符数 */
    @Value("${chunk.overlap:50}")
    private int CHUNK_OVERLAP;

    /** 段落分隔正则：匹配一个或多个空行 */
    private static final Pattern PARAGRAPH_PATTERN = Pattern.compile("(?m)^[\\s]*$");

    /**
     * 默认分块方法
     * 
     * 根据配置的策略选择分块方式
     * @param text 输入文本
     * @return 文本块列表
     */
    public List<String> split(String text) {
        if ("paragraph".equalsIgnoreCase(chunkStrategy)) {
            return splitByParagraph(text);
        }
        return splitByFixedSize(text, CHUNK_SIZE, CHUNK_OVERLAP);
    }

    /**
     * 指定参数的分块方法
     * 
     * @param text 输入文本
     * @param chunkSize 块大小
     * @param overlap 重叠大小
     * @return 文本块列表
     */
    public List<String> split(String text, int chunkSize, int overlap) {
        return splitByFixedSize(text, chunkSize, overlap);
    }

    /**
     * 按段落分块
     * 
     * 逻辑：
     * 1. 按双换行符（\n\n）分割段落
     * 2. 每个段落作为一个块（如果不太长）
     * 3. 特别长的段落再用固定大小切分
     * 
     * 适用场景：文章、论文、小说等按段落组织的文档
     * 优点：保持语义完整性，每个块是自然段落
     */
    public List<String> splitByParagraph(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return chunks;
        }

        // 按双换行符分割段落
        String[] paragraphs = text.split("\\n\\n+");
        
        for (String paragraph : paragraphs) {
            paragraph = paragraph.trim();
            if (paragraph.isEmpty()) {
                continue;
            }
            
            // 如果段落长度在允许范围内，直接作为一块
            if (paragraph.length() <= CHUNK_SIZE) {
                chunks.add(paragraph);
            } else {
                // 段落太长，用固定大小切分
                List<String> subChunks = splitByFixedSize(paragraph, CHUNK_SIZE, CHUNK_OVERLAP);
                chunks.addAll(subChunks);
            }
        }
        
        return chunks;
    }

    /**
     * 固定大小分块
     * 
     * 逻辑：
     * 1. 从文本开头开始，每次取chunkSize个字符
     * 2. 移动窗口时跳过 overlap 个字符（与上一个块重叠）
     * 3. 重复直到文本结束
     * 
     * 示例（chunkSize=10, overlap=3）：
     * 文本: "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
     * 块1: "ABCDEFGHIJ" (索引0-9)
     * 块2: "HIJKLMNOPQ" (索引7-16，与块1重叠3个字符)
     * 块3: "QRSTUVWXYZ" (索引16-25)
     * 
     * 适用场景：技术文档、代码说明等结构化内容
     * 优点：均匀切分，每块大小一致
     */
    public List<String> splitByFixedSize(String text, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return chunks;
        }

        int start = 0;
        while (start < text.length()) {
            // 计算当前块的结束位置
            int end = Math.min(start + chunkSize, text.length());
            // 提取子串
            String chunk = text.substring(start, end);
            chunks.add(chunk);
            
            // 移动窗口：跳过 (chunkSize - overlap) 个字符
            start = start + (chunkSize - overlap);
            
            // 避免无限循环
            if (start >= text.length()) {
                break;
            }
        }
        return chunks;
    }

    /**
     * 获取当前使用的分块策略
     */
    public String getChunkStrategy() {
        return chunkStrategy;
    }
}
