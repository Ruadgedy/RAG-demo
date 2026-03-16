package com.ragqa.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本切片服务
 * 
 * 作用：将长文本切分成较小的片段（chunks）
 * 
 * 为什么要切片：
 * - LLM有上下文长度限制
 * - 向量检索更精确
 * - 控制回答的来源范围
 * 
 * 切片策略：
 * - 固定长度切片（默认500字符）
 * - 相邻切片有重叠（默认50字符），保证语义连贯
 */
@Service
public class TextSplitter {

    /** 默认切片大小（字符数） */
    private static final int CHUNK_SIZE = 500;
    
    /** 相邻切片重叠字符数 */
    private static final int CHUNK_OVERLAP = 50;

    /**
     * 使用默认参数切片
     * 
     * @param text 输入文本
     * @return 切片列表
     */
    public List<String> split(String text) {
        return split(text, CHUNK_SIZE, CHUNK_OVERLAP);
    }

    /**
     * 自定义切片参数
     * 
     * @param text       输入文本
     * @param chunkSize  切片大小（字符数）
     * @param overlap    相邻切片重叠字符数
     * @return 切片列表
     * 
     * 示例：
     * text = "ABCDEFGHIJ", chunkSize = 4, overlap = 2
     * 结果: ["ABCD", "CDEF", "EFGH", "GHIJ"]
     */
    public List<String> split(String text, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return chunks;
        }

        int start = 0;
        while (start < text.length()) {
            // 截取切片
            int end = Math.min(start + chunkSize, text.length());
            String chunk = text.substring(start, end);
            chunks.add(chunk);
            
            // 移动起始位置（减去重叠部分）
            start = end - overlap;
            
            // 如果起始位置超出文本长度，停止
            if (start >= text.length()) {
                break;
            }
        }
        return chunks;
    }
}
