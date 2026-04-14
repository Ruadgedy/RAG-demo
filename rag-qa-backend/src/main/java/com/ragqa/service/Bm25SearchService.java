package com.ragqa.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * BM25 关键词检索服务
 *
 * 作用：基于 BM25 算法实现关键词检索，与向量检索形成互补
 *
 * 为什么需要 BM25：
 * 1. 向量检索擅长语义相似度匹配，但对专有名词、精确术语可能不准确
 * 2. BM25 基于词频和逆文档频率，擅长精确关键词匹配
 * 3. 两者结合（混合检索）能覆盖更多检索场景
 *
 * BM25 算法原理：
 * - 类似 TF-IDF，但考虑了文档长度归一化
 * - k1 和 b 是两个可调参数（通常 k1=1.5, b=0.75）
 * - score(Q,d) = Σ IDF(qi) × (tf(tfi,d) × (k1+1)) / (tf(tfi,d) + k1 × (1-b+b×|d|/avgdl))
 *
 * 与向量检索的融合策略（RRF）：
 * - Reciprocal Rank Fusion: score = Σ (1 / (k + rank_i))
 * - k 是平滑因子（通常 k=60）
 * - 对两个检索结果列表中的每个文档，按排名计算融合分数
 *
 * 使用场景：
 * - 用户问"Java 中的继承关键字是什么"
 * - 向量检索：找到语义相关但不含"extends"的文档
 * - BM25：精确匹配到含"extends"的文档
 * - 融合后：两者相关的结果都靠前
 */
@Service
@Slf4j
public class Bm25SearchService {

    /**
     * BM25 参数 k1
     *
     * 控制词频饱和度：
     * - k1 越大，词频对分数的影响越大
     * - k1=0 时，只考虑 IDF，不考虑词频
     * - 典型值：1.2 ~ 2.0
     */
    private static final double K1 = 1.5;

    /**
     * BM25 参数 b
     *
     * 控制文档长度归一化：
     * - b=1 时，完全按文档长度归一化
     * - b=0 时，不考虑文档长度差异
     * - 典型值：0.75
     */
    private static final double B = 0.75;

    /**
     * RRF 融合的平滑因子
     *
     * 值越大，不同排名之间的差异越小
     * 典型值：60
     */
    private static final int RRF_K = 60;

    /** 最小文档长度（用于平滑） */
    private static final double MIN_DOC_LENGTH = 1.0;

    /**
     * 文档块信息
     *
     * 存储文档的文本内容和元数据
     */
    public static class DocumentChunk {
        private final String id;           // 文档ID
        private final String content;      // 原始文本内容
        private final String documentId;   // 所属文档ID
        private final int chunkIndex;      // 切片索引

        public DocumentChunk(String id, String content, String documentId, int chunkIndex) {
            this.id = id;
            this.content = content;
            this.documentId = documentId;
            this.chunkIndex = chunkIndex;
        }

        public String getId() { return id; }
        public String getContent() { return content; }
        public String getDocumentId() { return documentId; }
        public int getChunkIndex() { return chunkIndex; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            DocumentChunk that = (DocumentChunk) o;
            return Objects.equals(id, that.id);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id);
        }
    }

    /**
     * 检索结果
     *
     * 包含文档信息和相关性分数
     */
    public static class Bm25Result {
        private final DocumentChunk chunk;
        private final double score;

        public Bm25Result(DocumentChunk chunk, double score) {
            this.chunk = chunk;
            this.score = score;
        }

        public DocumentChunk getChunk() { return chunk; }
        public double getScore() { return score; }

        /** 获取文档内容 */
        public String getContent() { return chunk.getContent(); }
    }

    /** 文档集合：chunkId -> DocumentChunk */
    private final Map<String, DocumentChunk> documents = new HashMap<>();

    /** 倒排索引：term -> {chunkId -> tf} */
    private final Map<String, Map<String, Double>> invertedIndex = new HashMap<>();

    /** 文档长度：chunkId -> 词数 */
    private final Map<String, Integer> docLengths = new HashMap<>();

    /** 平均文档长度 */
    private double averageDocLength = 0;

    /** 文档总数 */
    private int totalDocs = 0;

    /** IDF 缓存：term -> IDF分数 */
    private final Map<String, Double> idfCache = new HashMap<>();

    /**
     * 添加文档到索引
     *
     * 构建 BM25 所需的倒排索引结构
     *
     * @param chunkId 切片ID
     * @param content 文本内容
     * @param documentId 所属文档ID
     * @param chunkIndex 切片索引
     */
    public void addDocument(String chunkId, String content, String documentId, int chunkIndex) {
        // 创建文档对象
        DocumentChunk chunk = new DocumentChunk(chunkId, content, documentId, chunkIndex);
        documents.put(chunkId, chunk);

        // 分词（简单的中文分词 + 英文分词）
        List<String> terms = tokenize(content);

        // 计算文档长度（词数）
        int docLength = terms.size();
        docLengths.put(chunkId, docLength);

        // 更新平均文档长度
        totalDocs++;
        averageDocLength = (averageDocLength * (totalDocs - 1) + docLength) / totalDocs;

        // 构建倒排索引
        Map<String, Integer> termFreq = new HashMap<>();
        for (String term : terms) {
            termFreq.put(term, termFreq.getOrDefault(term, 0) + 1);
        }

        for (Map.Entry<String, Integer> entry : termFreq.entrySet()) {
            String term = entry.getKey();
            double tf = entry.getValue();

            // 添加到倒排索引
            invertedIndex.computeIfAbsent(term, k -> new HashMap<>()).put(chunkId, tf);
        }

        // 清除 IDF 缓存（因为文档集合变了）
        idfCache.clear();
    }

    /**
     * 批量添加文档
     */
    public void addDocuments(List<DocumentChunk> chunks) {
        for (DocumentChunk chunk : chunks) {
            addDocument(chunk.getId(), chunk.getContent(), chunk.getDocumentId(), chunk.getChunkIndex());
        }
    }

    /**
     * 清空索引
     */
    public void clear() {
        documents.clear();
        invertedIndex.clear();
        docLengths.clear();
        idfCache.clear();
        averageDocLength = 0;
        totalDocs = 0;
    }

    /**
     * 执行 BM25 检索
     *
     * @param query 查询文本
     * @param topK 返回的最多结果数
     * @return 按相关性分数降序排列的结果列表
     */
    public List<Bm25Result> search(String query, int topK) {
        if (documents.isEmpty()) {
            return Collections.emptyList();
        }

        // 对查询分词
        List<String> queryTerms = tokenize(query);

        // 计算每个文档的 BM25 分数
        Map<String, Double> scores = new HashMap<>();

        for (String chunkId : documents.keySet()) {
            double score = calculateBm25Score(chunkId, queryTerms);
            if (score > 0) {
                scores.put(chunkId, score);
            }
        }

        // 按分数降序排序
        List<Map.Entry<String, Double>> sorted = new ArrayList<>(scores.entrySet());
        sorted.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        // 取 Top-K
        List<Bm25Result> results = new ArrayList<>();
        int count = 0;
        for (Map.Entry<String, Double> entry : sorted) {
            if (count >= topK) break;
            DocumentChunk chunk = documents.get(entry.getKey());
            results.add(new Bm25Result(chunk, entry.getValue()));
            count++;
        }

        return results;
    }

    /**
     * 计算单个文档的 BM25 分数
     *
     * score = Σ IDF(qi) × (tf(tfi,d) × (k1+1)) / (tf(tfi,d) + k1 × (1-b+b×|d|/avgdl))
     *
     * @param chunkId 文档ID
     * @param queryTerms 查询词列表
     * @return BM25 分数
     */
    private double calculateBm25Score(String chunkId, List<String> queryTerms) {
        double score = 0;
        int docLength = docLengths.getOrDefault(chunkId, 0);
        double normalizedLength = Math.max(docLength, MIN_DOC_LENGTH) / Math.max(averageDocLength, MIN_DOC_LENGTH);

        for (String term : queryTerms) {
            // 获取该词在该文档中的词频
            Map<String, Double> posting = invertedIndex.get(term);
            double tf = (posting != null) ? posting.getOrDefault(chunkId, 0.0) : 0;

            // 计算 IDF
            double idf = calculateIdf(term);

            // BM25 公式
            double numerator = tf * (K1 + 1);
            double denominator = tf + K1 * (1 - B + B * normalizedLength);
            score += idf * numerator / denominator;
        }

        return score;
    }

    /**
     * 计算 IDF（逆文档频率）
     *
     * IDF = log((N - n(q) + 0.5) / (n(q) + 0.5))
     *
     * - N 是文档总数
     * - n(q) 是包含词 q 的文档数
     * - +0.5 是平滑项，避免 n(q)=N 时 IDF 为负
     *
     * @param term 查询词
     * @return IDF 分数
     */
    private double calculateIdf(String term) {
        // 检查缓存
        if (idfCache.containsKey(term)) {
            return idfCache.get(term);
        }

        // 计算包含该词的文档数
        Map<String, Double> posting = invertedIndex.get(term);
        int docFreq = (posting != null) ? posting.size() : 0;

        // 计算 IDF
        // 公式: log((N - n + 0.5) / (n + 0.5))
        // 如果词不在任何文档中，使用 N+1 作为平滑
        double idf;
        if (docFreq == 0) {
            // 词不在任何文档中，使用最大 IDF（平滑后接近 log(N)）
            idf = Math.log((totalDocs + 1) / 1.0);
        } else {
            idf = Math.log((totalDocs - docFreq + 0.5) / (docFreq + 0.5) + 1);
        }

        // 确保 IDF 非负
        idf = Math.max(idf, 0);

        // 缓存结果
        idfCache.put(term, idf);
        return idf;
    }

    /**
     * 简单分词
     *
     * 支持：
     * - 中文：按字符分词（简单方案，可替换为更高级的中文分词器）
     * - 英文：按空格和标点分词，转小写
     * - 数字和英文混合作为一个词
     *
     * @param text 输入文本
     * @return 词列表
     */
    private List<String> tokenize(String text) {
        List<String> terms = new ArrayList<>();

        if (text == null || text.isEmpty()) {
            return terms;
        }

        // 清理文本
        text = text.toLowerCase()
                   .replaceAll("[\\p{Punct}&&[^'-]]", " ")  // 保留撇号和连字符
                   .replaceAll("\\s+", " ")
                   .trim();

        // 分割
        String[] words = text.split("\\s+");
        for (String word : words) {
            word = word.trim();
            if (word.length() > 1) {  // 忽略单字符（除了一些中文）
                terms.add(word);
            }
        }

        return terms;
    }

    /**
     * 获取索引中的文档数量
     */
    public int getDocumentCount() {
        return totalDocs;
    }

    /**
     * 获取词汇表大小
     */
    public int getVocabularySize() {
        return invertedIndex.size();
    }
}
