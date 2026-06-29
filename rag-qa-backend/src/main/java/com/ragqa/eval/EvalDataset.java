package com.ragqa.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 黄金评估数据集（POJO）
 *
 * 一份数据集 = 一组 Q&A 对，用于评测 RAG 系统的检索 + 答案质量。
 *
 * 【2026-06-29 新增 P2-01】
 *
 * 数据集格式（JSON）：
 * <pre>
 * {
 *   "name": "product-manual-v1",
 *   "kbId": "uuid-...",                  // 可选：限制只在该 KB 上跑
 *   "description": "产品手册 v1 评测集",
 *   "items": [
 *     {
 *       "id": "q1",
 *       "question": "RAG 是什么？",
 *       "expectedDocIds": ["uuid-a", "uuid-b"],
 *       "expectedKeywords": ["检索增强", "embedding"],
 *       "goldenAnswer": "RAG 是检索增强生成..."    // 可选：用于答案层评估
 *     },
 *     ...
 *   ]
 * }
 * </pre>
 *
 * 设计要点：
 *   - expectedDocIds 必填：决定 Hit Rate / Recall / MRR 计算
 *   - expectedKeywords 可选：简单的关键词覆盖率（不需要 LLM）
 *   - goldenAnswer 可选：用于 LLM-judge faithfulness 比较
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class EvalDataset {

    /** 数据集名称（也是文件名去掉 .json） */
    private String name;

    /** 限定评测的知识库 UUID（可选，不指定则跑全部 KB） */
    @JsonProperty("kbId")
    private String kbId;

    /** 数据集描述 */
    private String description;

    /** 评测 Q&A 条目 */
    private List<EvalItem> items;

    /**
     * 单条评测条目
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EvalItem {
        /** 条目 ID（可选，用于报告里定位） */
        private String id;

        /** 用户问题（必填） */
        private String question;

        /**
         * 期望命中的文档 UUID 列表
         * 评估器会检查这些文档是否在检索 top-K 里出现
         */
        @JsonProperty("expectedDocIds")
        private List<String> expectedDocIds;

        /**
         * 期望出现的关键字（用于简单覆盖率评估，可选）
         */
        @JsonProperty("expectedKeywords")
        private List<String> expectedKeywords;

        /**
         * 黄金标准答案（用于 LLM-judge faithfulness 比较，可选）
         */
        @JsonProperty("goldenAnswer")
        private String goldenAnswer;
    }
}