package com.ragqa.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.Arrays;

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
 * 3. recursive（递归分块）：优先段落，其次句子，最后字符
 *
 * 递归分块流程：
 * ┌─────────────────────────┐
 * │     输入长文本           │
 * └───────────┬─────────────┘
 *             ▼
 * ┌─────────────────────────┐
 * │  Step 1: 按段落分块     │ ← 首选：保持语义完整性
 * └───────────┬─────────────┘
 *             ▼
 *      ┌──────────────┐
 *      │ 段落 ≤ 块大小 │──YES──▶ 保存为一块
 *      └──────┬───────┘
 *             │ NO
 *             ▼
 * ┌─────────────────────────┐
 * │  Step 2: 按句子分块     │ ← 次选：在句子边界切分
 * └───────────┬─────────────┘
 *             ▼
 *      ┌──────────────┐
 *      │ 句子 ≤ 块大小 │──YES──▶ 保存为一块
 *      └──────┬───────┘
 *             │ NO
 *             ▼
 * ┌─────────────────────────┐
 * │  Step 3: 硬字符切分     │ ← 最后：强制按字符数切分
 * └───────────┬─────────────┘
 *             ▼
 * ┌─────────────────────────┐
 * │     输出文本块列表       │
 * └─────────────────────────┘
 */
@Service
public class TextSplitter {

    /** 分块策略，可选值: fixed, paragraph, recursive */
    @Value("${chunk.strategy:recursive}")
    private String chunkStrategy;

    /** 每个切片的目标字符数 */
    @Value("${chunk.size:500}")
    private int CHUNK_SIZE;

    /** 相邻切片之间的重叠字符数 */
    @Value("${chunk.overlap:50}")
    private int CHUNK_OVERLAP;

    /** 最小块大小（递归时用于判断是否需要继续切分） */
    private static final int MIN_CHUNK_SIZE = 100;

    /** 句子结束标点（中文+英文） */
    private static final String SENTENCE_END_PUNCTUATION = "[。！？；.!?;]";

    /**
     * 默认分块方法
     *
     * 根据配置的策略选择分块方式
     * @param text 输入文本
     * @return 文本块列表
     */
    public List<String> split(String text) {
        if (text == null || text.isEmpty()) {
            return new ArrayList<>();
        }

        switch (chunkStrategy.toLowerCase()) {
            case "paragraph":
                return splitByParagraph(text);
            case "fixed":
                return splitByFixedSize(text, CHUNK_SIZE, CHUNK_OVERLAP);
            case "recursive":
            default:
                return splitRecursive(text, CHUNK_SIZE, CHUNK_OVERLAP);
        }
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
        return splitRecursive(text, chunkSize, overlap);
    }

    /**
     * ============================================================
     * 递归分块（核心方法）
     * ============================================================
     *
     * 递归分块的目标是：在保持语义完整性的前提下，尽可能大地分块
     *
     * 递归流程：
     * 1. 尝试按段落分块 → 如果段落大小合适，直接使用
     * 2. 如果段落太大 → 尝试按句子分块
     * 3. 如果句子还是太大 → 按字符数硬切
     *
     * 这样做的好处：
     * - 短段落保持完整（语义最完整）
     * - 中等段落按句子切分（句子是完整语义单元）
     * - 超长段落硬切（避免栈溢出）
     *
     * @param text 输入文本
     * @param targetSize 目标块大小
     * @param overlap 重叠大小
     * @return 文本块列表
     */
    public List<String> splitRecursive(String text, int targetSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return chunks;
        }

        // Step 1: 按段落分割
        List<String> paragraphs = splitIntoParagraphs(text);

        for (String paragraph : paragraphs) {
            // 清理空白
            paragraph = paragraph.trim();
            if (paragraph.isEmpty()) {
                continue;
            }

            // 段落大小合适，直接添加
            if (paragraph.length() <= targetSize) {
                chunks.add(paragraph);
                continue;
            }

            // 段落太大，递归按句子切分
            List<String> sentenceChunks = splitBySentenceRecursive(paragraph, targetSize, overlap);
            chunks.addAll(sentenceChunks);
        }

        return chunks;
    }

    /**
     * 按句子递归分块
     *
     * 当段落太大时，使用此方法：
     * 1. 将段落分割成句子
     * 2. 累积句子直到达到目标大小
     * 3. 累积的句子作为一个块
     * 4. 如果单个句子就超过目标大小，按字符硬切
     *
     * @param text 输入文本（单个段落）
     * @param targetSize 目标块大小
     * @param overlap 重叠大小
     * @return 文本块列表
     */
    private List<String> splitBySentenceRecursive(String text, int targetSize, int overlap) {
        List<String> chunks = new ArrayList<>();

        // Step 2: 按句子分割
        List<String> sentences = splitIntoSentences(text);

        // 累积变量
        StringBuilder currentChunk = new StringBuilder();

        for (String sentence : sentences) {
            sentence = sentence.trim();
            if (sentence.isEmpty()) {
                continue;
            }

            // 单个句子就超过目标大小，按字符硬切
            if (sentence.length() > targetSize) {
                // 先保存当前累积的块（如果非空）
                if (currentChunk.length() > 0) {
                    chunks.add(currentChunk.toString());
                    currentChunk = new StringBuilder();
                }

                // 递归硬切这个超长句子
                List<String> subChunks = splitByCharHard(sentence, targetSize, overlap);
                chunks.addAll(subChunks);
                continue;
            }

            // 累积这个句子
            // 判断：如果加上这个句子会不会超过目标大小
            if (currentChunk.length() + sentence.length() + 1 > targetSize && currentChunk.length() > 0) {
                // 保存当前块，开始新块
                chunks.add(currentChunk.toString());
                // 新块从上一个块的末尾取overlap，保持上下文连续
                String lastPart = getLastNChars(currentChunk.toString(), overlap);
                currentChunk = new StringBuilder(lastPart);
            }

            // 添加句子和分隔符
            if (currentChunk.length() > 0) {
                currentChunk.append(" "); // 句子之间加空格
            }
            currentChunk.append(sentence);
        }

        // 处理最后一块
        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString());
        }

        return chunks;
    }

    /**
     * 按字符硬切分（最后手段）
     *
     * 当句子太长无法按句子分时，强制按字符数切分
     * 这种方式可能切断句子，但能保证块大小可控
     *
     * @param text 输入文本
     * @param chunkSize 块大小
     * @param overlap 重叠大小
     * @return 文本块列表
     */
    private List<String> splitByCharHard(String text, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();

        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());
            String chunk = text.substring(start, end);
            chunks.add(chunk);

            // 移动窗口：跳过 (chunkSize - overlap) 个字符
            start = start + (chunkSize - overlap);
            if (start >= text.length()) {
                break;
            }
        }

        return chunks;
    }

    /**
     * 将文本分割成段落
     *
     * 段落分隔规则：
     * - 中文：两个以上换行符 \n\n 或 \n\n\n
     * - 英文：两个以上空行
     * - 单个换行后如果是小写开头，也认为是新段落（句子太长时的软换行）
     *
     * @param text 输入文本
     * @return 段落列表
     */
    private List<String> splitIntoParagraphs(String text) {
        List<String> paragraphs = new ArrayList<>();

        // 先按连续换行符分割
        String[] parts = text.split("\\n{2,}|\\n\\n+");

        for (String part : parts) {
            // 进一步处理单换行的情况
            // 如果段落以小写字母开头，且上一个段落以小写结尾，合并
            if (!paragraphs.isEmpty() && part.length() > 0) {
                char firstChar = part.charAt(0);
                String lastParagraph = paragraphs.get(paragraphs.size() - 1);
                if (lastParagraph.length() > 0) {
                    char lastChar = lastParagraph.charAt(lastParagraph.length() - 1);
                    // 如果上一段以小写字母结尾，且当前段以小写字母开头，合并
                    if (Character.isLowerCase(lastChar) && Character.isLowerCase(firstChar)) {
                        // 合并段落
                        paragraphs.set(paragraphs.size() - 1, lastParagraph + "\n" + part.trim());
                        continue;
                    }
                }
            }
            paragraphs.add(part.trim());
        }

        return paragraphs;
    }

    /**
     * 将文本分割成句子
     *
     * 句子分割规则：
     * - 中文句号：。！？；
     * - 英文句号：. ! ? ;
     * - 忽略缩写（Dr. Mr. Mrs. e.g. i.e. 等）
     *
     * @param text 输入文本
     * @return 句子列表
     */
    private List<String> splitIntoSentences(String text) {
        List<String> sentences = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return sentences;
        }

        // 使用正则按句末标点分割
        // (?<=[。！？；.!?;])  正向后断言，保留标点
        // + 表示一个或多个连续标点
        String[] parts = text.split("(?<=[。！？；.!?;])\\s*");

        for (String part : parts) {
            part = part.trim();
            if (!part.isEmpty()) {
                sentences.add(part);
            }
        }

        return sentences;
    }

    /**
     * 获取字符串末尾的N个字符
     *
     * 用于块之间的重叠部分，保持上下文连续
     *
     * @param text 输入文本
     * @param n 字符数
     * @return 末尾N个字符
     */
    private String getLastNChars(String text, int n) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        int start = Math.max(0, text.length() - n);
        return text.substring(start);
    }

    /**
     * 按段落分块（兼容旧代码）
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
                // 段落太长，用递归方式切分（保持句子完整性）
                List<String> subChunks = splitRecursive(paragraph, CHUNK_SIZE, CHUNK_OVERLAP);
                chunks.addAll(subChunks);
            }
        }

        return chunks;
    }

    /**
     * 固定大小分块（兼容旧代码）
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

    /**
     * 获取配置的分块大小
     */
    public int getChunkSize() {
        return CHUNK_SIZE;
    }

    /**
     * 获取配置的重叠大小
     */
    public int getOverlap() {
        return CHUNK_OVERLAP;
    }
}
