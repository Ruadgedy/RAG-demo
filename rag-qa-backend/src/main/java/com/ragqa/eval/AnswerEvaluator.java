package com.ragqa.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 答案层 LLM-judge 评估器
 *
 * 调用现有 MiniMax-M2.5 作为评判模型，对每条 Q&A 的最终答案打两个分：
 *   - Faithfulness（忠实度）：答案是否完全基于检索上下文，无编造
 *   - Relevance（相关性）：答案是否切题
 *
 * 【2026-06-29 新增 P2-01】
 *
 * 设计取舍：
 *   1. 复用项目已有的 Spring AI ChatClient（与生产对话同款），无额外依赖
 *   2. prompt 模板放 resources/eval/judge-prompt.txt，方便迭代调优
 *   3. 评分 1-5 整数（与 RAGAS 同），便于聚合算平均
 *   4. 同时让 LLM 列出无支撑的声明（unsupported_claims），便于人工定位问题
 *
 * 成本估算（MiniMax-M2.5 价格）：
 *   - 每次调用 prompt ≈ 800 tokens，response ≈ 200 tokens
 *   - 评测 20 条 ≈ ¥0.1 量级，可接受
 */
@Component
@Slf4j
public class AnswerEvaluator {

    private final ChatClient.Builder chatClientBuilder;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 默认模型（与生产对话一致） */
    @Value("${spring.ai.minimax.chat.options.model:MiniMax-M2.5}")
    private String defaultModel;

    /**
     * 单条答案的 LLM-judge 评分结果
     *
     * @param faithfulness  1-5 分，0 表示 LLM 调用失败
     * @param relevance     1-5 分，0 表示 LLM 调用失败
     * @param unsupportedClaims LLM 列出的无支撑声明（便于调试）
     * @param rawResponse   LLM 原始返回（失败时存错误信息，便于排查）
     */
    public record Score(
            double faithfulness,
            double relevance,
            List<String> unsupportedClaims,
            String rawResponse
    ) {}

    public AnswerEvaluator(ChatClient.Builder chatClientBuilder) {
        this.chatClientBuilder = chatClientBuilder;
    }

    /**
     * 评估一条 Q&A 的答案
     *
     * @param question 用户问题
     * @param context  检索上下文（多段拼接）
     * @param answer   AI 生成的答案
     * @return Score（faithfulness/relevance 失败时返回 0）
     */
    public Score evaluate(String question, String context, String answer) {
        if (answer == null || answer.isBlank()) {
            return new Score(0, 0, List.of(), "EMPTY_ANSWER");
        }

        try {
            String promptTemplate = loadPromptTemplate();
            String prompt = promptTemplate
                    .replace("{question}", question == null ? "" : question)
                    .replace("{context}", context == null ? "" : context)
                    .replace("{answer}", answer);

            String response = chatClientBuilder.build()
                    .prompt(prompt)
                    .call()
                    .content();

            return parseResponse(response);
        } catch (Exception e) {
            log.warn("LLM-judge 调用失败: {}", e.getMessage());
            return new Score(0, 0, List.of(), "ERROR: " + e.getMessage());
        }
    }

    /**
     * 加载 judge prompt 模板（从 classpath 读，缓存到静态字段）
     */
    private String loadPromptTemplate() throws Exception {
        if (cachedPromptTemplate == null) {
            try (var in = new ClassPathResource("eval/judge-prompt.txt").getInputStream()) {
                cachedPromptTemplate = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        return cachedPromptTemplate;
    }

    /** 静态缓存（避免每次评测重读文件） */
    private volatile String cachedPromptTemplate = null;

    /**
     * 解析 LLM 返回的 JSON
     *
     * LLM 偶尔会返回带 markdown 代码块包裹的 JSON（```json ... ```），
     * 需要做兜底解析。同时字段容错：faithfulness / relevance 缺失时填 0。
     */
    private Score parseResponse(String rawResponse) {
        if (rawResponse == null || rawResponse.isBlank()) {
            return new Score(0, 0, List.of(), "EMPTY_RESPONSE");
        }

        // 清理可能的 markdown 包裹
        String cleaned = rawResponse.trim();
        if (cleaned.startsWith("```")) {
            // 去掉首尾 ``` 或 ```json
            int firstNewline = cleaned.indexOf('\n');
            if (firstNewline > 0) cleaned = cleaned.substring(firstNewline + 1);
            if (cleaned.endsWith("```")) {
                cleaned = cleaned.substring(0, cleaned.length() - 3);
            }
            cleaned = cleaned.trim();
        }

        try {
            JsonNode root = objectMapper.readTree(cleaned);
            double faithfulness = root.has("faithfulness") ? root.get("faithfulness").asDouble() : 0;
            double relevance = root.has("relevance") ? root.get("relevance").asDouble() : 0;

            // 范围裁剪（防止 LLM 输出 7 或 -1 这种异常值）
            faithfulness = Math.max(0, Math.min(5, faithfulness));
            relevance = Math.max(0, Math.min(5, relevance));

            List<String> unsupported = new ArrayList<>();
            if (root.has("unsupported_claims") && root.get("unsupported_claims").isArray()) {
                root.get("unsupported_claims").forEach(n -> unsupported.add(n.asText()));
            }
            return new Score(faithfulness, relevance, unsupported, rawResponse);
        } catch (Exception e) {
            log.warn("LLM-judge 响应解析失败: response='{}', error={}",
                    rawResponse.substring(0, Math.min(200, rawResponse.length())), e.getMessage());
            return new Score(0, 0, Collections.emptyList(), "PARSE_ERROR: " + rawResponse);
        }
    }
}