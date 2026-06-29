package com.ragqa.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文档来源引用（前端"参考文档"卡片数据源）
 *
 * 作用：把 RAG 召回的每个文档切片暴露给前端，让用户能看到 AI 答案的依据。
 *
 * 【2026-06-29 增量 P0-01】
 * 历史问题：LLM 被要求用【文档X】标注来源，但前端只收到 answer 字符串，
 * 永远不知道【文档X】到底是哪个文档。这导致答案可信度为 0。
 *
 * 修复方案：
 *   1. RagService 在 retrieve() 时把 docId 解析成 fileName + 截取 snippet
 *   2. ChatService 把 RetrievalResult 转换为 List&lt;SourceRef&gt;
 *   3. ChatResponse 加 sources 字段
 *   4. 流式接口在收尾时单独发一个 SSE event: sources 携带同样的 JSON
 *   5. 前端 MessageBubble 渲染为「参考 3 篇文档 ▾」可展开卡片
 *
 * 字段说明：
 *   - documentId  文档 UUID（String 形式）
 *   - fileName    原始文件名（如 "产品手册.pdf"），用户最容易理解的标识
 *   - chunkIndex  切片在文档中的索引（0-based）
 *   - snippet     切片内容摘要（前 snippet-length 字符，默认 200），避免传输全文
 *   - score       相关性分数（cosine 或 rerank score，范围 [-1, 1] 或 [0, 1]）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SourceRef {

    /** 文档 UUID（字符串形式，避免前端处理 UUID 类型） */
    private String documentId;

    /** 原始文件名（如 "产品手册.pdf"）—— 用户最容易理解的标识 */
    private String fileName;

    /** 切片在文档中的索引（0-based） */
    private Integer chunkIndex;

    /**
     * 切片内容摘要（前 N 字符，默认 200）
     * 避免传输全文（一段可能几千字，浪费带宽）
     */
    private String snippet;

    /**
     * 相关性分数
     * - cosine 重排：[-1, 1]，越接近 1 越相关
     * - cross-encoder 重排：由模型决定，常见 [0, 1]
     */
    private Double score;
}