package com.ragqa.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * ============================================================
 * BM25 关键词检索服务
 * ============================================================
 *
 * 【功能说明】
 * BM25 是一种经典的信息检索算法，用于计算文档与查询之间的相关性评分。
 * 与向量检索（语义相似度）不同，BM25 专注于关键词的精确匹配。
 *
 * 【为什么需要 BM25】
 * 1. 向量检索擅长语义匹配："如何戒烟" 能匹配到 "减少抽烟的方法"
 * 2. 但对于专有名词、精确术语，向量检索可能不准确
 * 3. BM25 基于词频统计，精确匹配关键词
 * 4. 两者结合 = 混合检索，兼顾语义和精确
 *
 * 【BM25 算法公式】
 * score(Q,d) = Σ IDF(qi) × (tf(tfi,d) × (k1+1)) / (tf(tfi,d) + k1×(1-b+b×|d|/avgdl))
 *
 * 其中：
 * - tf(tfi,d): 词 tfi 在文档 d 中的词频
 * - IDF(qi): 逆文档频率，词越常见权重越低
 * - |d|: 文档长度
 * - avgdl: 平均文档长度
 * - k1, b: 可调参数（k1=1.5, b=0.75）
 *
 * 【使用示例】
 * 用户问："Java 中的继承关键字是什么"
 * - 向量检索可能找到语义相关但不含 "extends" 的文档
 * - BM25 精确匹配到含 "extends" 的文档
 * - 混合检索后两者都靠前
 */
@Service
@Slf4j
public class Bm25SearchService {

    // ============================================================
    // BM25 算法核心参数
    // ============================================================

    /**
     * BM25 词频饱和度参数
     *
     * 【作用】控制词频对分数的影响程度
     * - k1=0：只考虑 IDF，不考虑词频（所有词只算一次）
     * - k1 越大：词频对分数影响越大
     * - 典型值：1.2 ~ 2.0
     *
     * 【通俗理解】
     * 想象你统计一本书中某个词出现的次数：
     * - k1 很小时：出现 1 次和出现 100 次差别不大
     * - k1 很大时：出现 100 次的词比出现 1 次的词重要得多
     */
    private static final double K1 = 1.5;

    /**
     * BM25 文档长度归一化参数
     *
     * 【作用】平衡不同长度文档的评分
     * - b=0：不考虑文档长度差异
     * - b=1：完全按文档长度归一化
     * - 典型值：0.75
     *
     * 【通俗理解】
     * 长文档天然包含更多词，所以词频会更高。
     * b 参数就是用来「惩罚」长文档，避免长文档总是排在前面。
     */
    private static final double B = 0.75;

    /**
     * RRF 融合的平滑因子
     *
     * 【作用】在多检索结果融合时，平滑不同排名之间的差异
     * - k 越大，不同排名之间的分数差异越小
     * - 典型值：60
     *
     * 【公式】
     * RRF_score(d) = Σ 1 / (k + rank_i(d))
     *
     * 【示例】
     * 假设有两个排名列表：
     * - A 在列表1排第1，在列表2排第3
     * - B 在列表1排第2，在列表2排第2
     * RRF 分数：
     * - A: 1/(60+1) + 1/(60+3) = 0.0164 + 0.0159 = 0.0323
     * - B: 1/(60+2) + 1/(60+2) = 0.0159 + 0.0159 = 0.0317
     * A 排第一（综合排名更好）
     */
    private static final int RRF_K = 60;

    /** 最小文档长度（用于避免除零错误） */
    private static final double MIN_DOC_LENGTH = 1.0;

    // ============================================================
    // 索引数据结构
    // ============================================================

    /**
     * 文档块信息
     *
     * 【说明】存储单个文档切片的基本信息
     * - id: 切片唯一标识（格式：documentId_chunkIndex）
     * - content: 原始文本内容
     * - documentId: 所属文档ID
     * - chunkIndex: 在文档中的切片索引
     */
    public static class DocumentChunk {
        private final String id;
        private final String content;
        private final String documentId;
        private final int chunkIndex;

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
     * BM25 检索结果
     *
     * 【说明】包含文档切片和对应的 BM25 相关性分数
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
        public String getContent() { return chunk.getContent(); }
    }

    // ============================================================
    // 索引存储
    // ============================================================

    /** 文档集合：chunkId -> DocumentChunk */
    // 【说明】存储所有已索引的文档切片，供检索使用
    private final Map<String, DocumentChunk> documents = new HashMap<>();

    /** 倒排索引：term -> {chunkId -> tf} */
    // 【核心数据结构】倒排索引是 BM25 高效检索的关键
    // 正排索引：文档 -> 包含的词（我们平时的理解方式）
    // 倒排索引：词 -> 包含该词的文档列表（搜索引擎用的方式）
    // 【示例】
    // 如果正排是：{"doc1": ["苹果", "手机"], "doc2": ["苹果", "香蕉"]}
    // 那么倒排就是：{"苹果": ["doc1", "doc2"], "手机": ["doc1"], "香蕉": ["doc2"]}
    private final Map<String, Map<String, Double>> invertedIndex = new HashMap<>();

    /** 文档长度记录：chunkId -> 词数（不是字符数） */
    // 【注意】是词数，不是字符数或字节数
    private final Map<String, Integer> docLengths = new HashMap<>();

    /** 平均文档长度（所有文档长度的平均值） */
    private double averageDocLength = 0;

    /** 已索引的文档总数 */
    private int totalDocs = 0;

    /** IDF 缓存：term -> IDF分数（避免重复计算） */
    private final Map<String, Double> idfCache = new HashMap<>();

    // ============================================================
    // 核心方法
    // ============================================================

    /**
     * 添加单个文档到索引
     *
     * 【流程】
     * 1. 创建 DocumentChunk 对象存储文档信息
     * 2. 对文档内容进行分词
     * 3. 统计每个词在文档中的出现次数（词频 tf）
     * 4. 更新倒排索引
     * 5. 更新平均文档长度
     *
     * 【分词策略】
     * - 英文：按空格和标点分割，转小写
     * - 中文：按字符分割（简单方案，可替换为专业分词器如 jieba）
     * - 忽略单字符（除非是中文）
     *
     * @param chunkId 切片ID（格式：documentId_chunkIndex）
     * @param content 文本内容
     * @param documentId 所属文档ID
     * @param chunkIndex 切片索引
     */
    public void addDocument(String chunkId, String content, String documentId, int chunkIndex) {
        // Step 1: 创建文档对象
        DocumentChunk chunk = new DocumentChunk(chunkId, content, documentId, chunkIndex);
        documents.put(chunkId, chunk);

        // Step 2: 分词
        // 【分词示例】
        // 输入: "Hello, World! 你好，世界！"
        // 输出: ["hello", "world", "你", "好", "世", "界"]
        List<String> terms = tokenize(content);

        // Step 3: 统计词频
        // Map<词, 出现次数>
        // 【示例】
        // "Java Java Java Python"
        // -> {"java": 3, "python": 1}
        Map<String, Integer> termFreq = new HashMap<>();
        for (String term : terms) {
            termFreq.put(term, termFreq.getOrDefault(term, 0) + 1);
        }

        // Step 4: 更新文档长度统计
        int docLength = terms.size();
        docLengths.put(chunkId, docLength);
        totalDocs++;
        averageDocLength = (averageDocLength * (totalDocs - 1) + docLength) / totalDocs;

        // Step 5: 更新倒排索引
        // 【核心操作】
        // 对于每个词 term，将当前文档添加到 term 的倒排列表中
        // 倒排列表存储：(chunkId -> 词频)
        for (Map.Entry<String, Integer> entry : termFreq.entrySet()) {
            String term = entry.getKey();
            double tf = entry.getValue();  // 词频

            // computeIfAbsent: 如果 term 不存在，创建一个新的 HashMap
            // 然后把 (chunkId -> tf) 添加进去
            invertedIndex.computeIfAbsent(term, k -> new HashMap<>()).put(chunkId, tf);
        }

        // 清除 IDF 缓存（因为文档集合变了，需要重新计算）
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
     * 【流程】
     * 1. 对查询进行分词
     * 2. 计算每个文档的 BM25 分数
     * 3. 按分数降序排序
     * 4. 返回 Top-K 结果
     *
     * 【分数计算】
     * 对于查询 Q = {q1, q2, ..., qn}，文档 d 的 BM25 分数为：
     * score(Q,d) = Σ IDF(qi) × (tf(tfi,d) × (k1+1)) / (tf(tfi,d) + k1×(1-b+b×|d|/avgdl))
     *
     * @param query 查询文本
     * @param topK 返回的结果数量
     * @return 按相关性分数降序排列的结果列表
     */
    public List<Bm25Result> search(String query, int topK) {
        if (documents.isEmpty()) {
            return Collections.emptyList();
        }

        // Step 1: 查询分词
        // 【示例】
        // 查询: "Java 继承"
        // 词: ["java", "继承"]
        List<String> queryTerms = tokenize(query);

        // Step 2: 计算每个文档的 BM25 分数
        Map<String, Double> scores = new HashMap<>();

        for (String chunkId : documents.keySet()) {
            // 计算单个文档的分数
            double score = calculateBm25Score(chunkId, queryTerms);
            // 只保留分数 > 0 的文档（没有任何匹配词的文档分数为 0）
            if (score > 0) {
                scores.put(chunkId, score);
            }
        }

        // Step 3: 按分数降序排序
        List<Map.Entry<String, Double>> sorted = new ArrayList<>(scores.entrySet());
        sorted.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        // Step 4: 取 Top-K
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
     * 【公式分解】
     * score(term, doc) = IDF(term) × (tf(term,doc) × (k1+1)) / (tf(term,doc) + k1×(1-b+b×|doc|/avgdl))
     *
     * 公式各部分解释：
     * - IDF(term): 逆文档频率，词越常见分数越低（惩罚常见词）
     * - tf(term,doc): 词在文档中的出现次数
     * - |doc|/avgdl: 文档长度相对于平均长度的比例
     * - (k1+1)/(tf + k1×...): 词频的饱和函数，避免 tf 过大时分数无限增长
     *
     * 【通俗理解】
     * 这个公式在说：
     * 1. 不常见的词更重要（IDF 高）
     * 2. 在文档中出现次数多的词更重要（tf 高）
     * 3. 但不能无限重要，当 tf 很大时，增加一个词频带来的分数增长变少（饱和）
     * 4. 长文档天然词多，所以要除以文档长度（归一化）
     */
    private double calculateBm25Score(String chunkId, List<String> queryTerms) {
        double totalScore = 0;

        // 获取文档长度
        int docLength = docLengths.getOrDefault(chunkId, 0);
        // 文档长度归一化：避免除零，同时平滑长度差异
        // 归一化后的长度：如果文档 = 平均长度，则值为 1
        double normalizedLength = Math.max(docLength, MIN_DOC_LENGTH) / Math.max(averageDocLength, MIN_DOC_LENGTH);

        // 遍历查询中的每个词
        for (String term : queryTerms) {
            // 获取该词在该文档中的词频
            // posting: Map<chunkId, tf>
            Map<String, Double> posting = invertedIndex.get(term);
            double tf = (posting != null) ? posting.getOrDefault(chunkId, 0.0) : 0;

            // 如果词在文档中出现次数为 0，跳过
            if (tf == 0) {
                continue;
            }

            // 计算 IDF
            double idf = calculateIdf(term);

            // BM25 公式
            // numerator: tf * (k1 + 1)
            // denominator: tf + k1 * (1 - b + b * normalizedLength)
            double numerator = tf * (K1 + 1);
            double denominator = tf + K1 * (1 - B + B * normalizedLength);

            // 累加每个词的分数
            totalScore += idf * numerator / denominator;
        }

        return totalScore;
    }

    /**
     * 计算 IDF（逆文档频率）
     *
     * 【公式】
     * IDF(term) = log((N - n + 0.5) / (n + 0.5))
     *
     * 其中：
     * - N: 文档总数
     * - n: 包含该词的文档数
     * - 0.5: 平滑项，避免 n=N 时 IDF 为负数
     *
     * 【IDF 的含义】
     * - 如果一个词在所有文档中都出现（n = N），则 IDF = log(1) = 0
     *   → 这个词没有区分度，不重要
     * - 如果一个词只在少数文档中出现（n 很小），则 IDF 很大
     *   → 这个词有很强的区分度，很重要
     *
     * 【示例】
     * 假设有 100 个文档：
     * - "的" 出现在 95 个文档中 → n=95 → IDF ≈ log((100-95+0.5)/(95+0.5)) ≈ log(0.05) ≈ -3
     * - "机器学习" 出现在 5 个文档中 → n=5 → IDF ≈ log((100-5+0.5)/(5+0.5)) ≈ log(17) ≈ 2.8
     * → "机器学习"的 IDF 高很多，说明这个词更有区分度
     */
    private double calculateIdf(String term) {
        // 检查缓存，避免重复计算
        if (idfCache.containsKey(term)) {
            return idfCache.get(term);
        }

        // 计算包含该词的文档数
        Map<String, Double> posting = invertedIndex.get(term);
        int docFreq = (posting != null) ? posting.size() : 0;

        // 计算 IDF
        double idf;
        if (docFreq == 0) {
            // 词不在任何文档中，使用最大 IDF（相当于很罕见的词）
            // log(N+1) 是平滑后的最大 IDF
            idf = Math.log((totalDocs + 1) / 1.0);
        } else {
            // 标准 IDF 公式
            idf = Math.log((totalDocs - docFreq + 0.5) / (docFreq + 0.5) + 1);
        }

        // 确保 IDF 非负（数学上可能出现负数，这里做保护）
        idf = Math.max(idf, 0);

        // 缓存结果
        idfCache.put(term, idf);
        return idf;
    }

    /**
     * 简单分词器
     *
     * 【支持的语言】
     * - 英文：按空格和标点分割，转小写
     * - 中文：按字符分割（简单方案）
     *
     * 【改进空间】
     * 当前是按字符分割中文，更好的方案是：
     * - 使用结巴分词（jieba）
     * - 使用 HanLP
     * - 使用 IK Analyzer
     *
     * 【为什么中文按字符】
     * 简单！但问题是：
     * - "机器学习" 会被分成 ["机", "器", "学", "习"]
     * - 检索 "机器学习" 时，会匹配到分别包含这四个字的任意文档
     * - 这可能导致误匹配
     *
     * 【处理步骤】
     * 1. 转小写（英文）
     * 2. 移除标点符号（保留撇号和连字符，如 don't, well-known）
     * 3. 按空白分割成词
     * 4. 过滤单字符（英文单字符无意义）
     */
    private List<String> tokenize(String text) {
        List<String> terms = new ArrayList<>();

        if (text == null || text.isEmpty()) {
            return terms;
        }

        // 清理文本
        // 1. 转小写（统一大小写）
        // 2. 移除标点，但保留撇号(')和连字符(-)，因为它们是单词的一部分
        //    例如: don't -> don, t; well-known -> well, known
        // 3. 多个空白合并为一个
        text = text.toLowerCase()
                   .replaceAll("[\\p{Punct}&&[^'-]]", " ")  // 保留 ' 和 -
                   .replaceAll("\\s+", " ")
                   .trim();

        // 按空白分割
        String[] words = text.split("\\s+");
        for (String word : words) {
            word = word.trim();
            // 忽略单字符（英文的 a, I 等除外，但中文单字也没意义）
            if (word.length() > 1) {
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
     * 获取词汇表大小（不同词的数量）
     */
    public int getVocabularySize() {
        return invertedIndex.size();
    }
}
