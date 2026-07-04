package com.ragqa.poc;

import io.github.cdimascio.dotenv.Dotenv;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 【Agentic RAG 升级 - PoC】MiniMax-M3 + Spring AI 1.1.3 Tool Calling 可行性验证
 *
 * <p>验证目标（Agentic RAG 的技术地基）：
 * <ol>
 *   <li>MiniMax-M3 能否发起 function call（LLM 返回 tool_call）</li>
 *   <li>Spring AI 框架能否自动执行 @Tool 方法并回填结果</li>
 *   <li>是否支持多轮 tool-calling（连续调多个 tool，P2 agent loop 地基）</li>
 *   <li>无需 tool 时 LLM 能否正确判断不调</li>
 * </ol>
 *
 * <p>运行方式（默认跳过，避免污染 mvn test / CI）：
 * <pre>
 * mvn test -Dtest=MiniMaxToolCallingPoCTest -Drag.poc=true -DfailIfNoTests=false
 * </pre>
 *
 * <p>上下文隔离：用 @ImportAutoConfiguration 只加载 MiniMax + ChatClient 自动配置，
 * 不触发 DB / Security / Chroma，保证 PoC 最小启动。
 */
@SpringBootTest(
        classes = MiniMaxToolCallingPoCTest.PoCConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.ai.vectorstore.chroma.enabled=false"
)
@EnabledIfSystemProperty(named = "rag.poc", matches = "true")
class MiniMaxToolCallingPoCTest {

    /** 主回答模型（与 application.properties 保持一致） */
    private static final String MODEL = "MiniMax-M3";

    @Autowired
    ChatClient.Builder chatClientBuilder;

    @Autowired
    PoCTools tools;

    /** 从 .env 加载 MiniMax 凭证到 System properties（复用主类 RagQaApplication 的加载方式） */
    @BeforeAll
    static void loadEnv() {
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
        dotenv.entries().forEach(e -> System.setProperty(e.getKey(), e.getValue()));
        // 兜底显式声明（占位符解析优先级：System properties 高）
        System.setProperty("spring.ai.minimax.base-url", "https://api.minimax.chat");
        System.setProperty("spring.ai.minimax.chat.options.model", MODEL);
    }

    // ========== 用例 1：单 tool 调用 ==========
    @Test
    void testSingleToolCall() {
        tools.reset();
        String answer = chatClientBuilder.build()
                .prompt()
                .user("现在服务器几点了？请用工具查一下")
                .tools(tools)
                .options(ToolCallingChatOptions.builder()
                        .model(MODEL)
                        .internalToolExecutionEnabled(true)
                        .build())
                .call()
                .content();

        System.out.println("[PoC1 单tool] answer=" + answer);
        System.out.println("[PoC1 单tool] getServerTime 调用次数=" + tools.timeCalls.get());
        assertThat(tools.timeCalls.get()).isGreaterThan(0);
    }

    // ========== 用例 2：知识库风格 tool（验证基于 tool 结果回答） ==========
    @Test
    void testKnowledgeBasedTool() {
        tools.reset();
        String answer = chatClientBuilder.build()
                .prompt()
                .user("产品A卖多少钱？")
                .tools(tools)
                .options(ToolCallingChatOptions.builder()
                        .model(MODEL)
                        .internalToolExecutionEnabled(true)
                        .build())
                .call()
                .content();

        System.out.println("[PoC2 KB风格] answer=" + answer);
        System.out.println("[PoC2 KB风格] searchProduct 调用次数=" + tools.searchCalls.get() + ", 参数=" + tools.searchedProducts);
        assertThat(tools.searchCalls.get()).isGreaterThan(0);
        assertThat(answer).contains("2999");
    }

    // ========== 用例 3：多轮 tool-calling（P2 agent loop 地基） ==========
    @Test
    void testMultiToolCall() {
        tools.reset();
        String answer = chatClientBuilder.build()
                .prompt()
                .user("产品A和产品B的价格分别是多少？请分别告诉我")
                .tools(tools)
                .options(ToolCallingChatOptions.builder()
                        .model(MODEL)
                        .internalToolExecutionEnabled(true)
                        .build())
                .call()
                .content();

        System.out.println("[PoC3 多tool] answer=" + answer);
        System.out.println("[PoC3 多tool] searchProduct 调用次数=" + tools.searchCalls.get() + ", 参数=" + tools.searchedProducts);
        // P2 地基：一次问答里 LLM 连续调用 ≥2 次 tool
        assertThat(tools.searchCalls.get()).isGreaterThanOrEqualTo(2);
        assertThat(answer).contains("2999").contains("4599");
    }

    // ========== 用例 4：无需 tool 时不调用 ==========
    @Test
    void testNoToolNeeded() {
        tools.reset();
        String answer = chatClientBuilder.build()
                .prompt()
                .user("你好，请用一句话介绍你自己")
                .tools(tools)
                .options(ToolCallingChatOptions.builder()
                        .model(MODEL)
                        .internalToolExecutionEnabled(true)
                        .build())
                .call()
                .content();

        System.out.println("[PoC4 无需tool] answer=" + answer);
        System.out.println("[PoC4 无需tool] tool 调用次数 time=" + tools.timeCalls.get() + " search=" + tools.searchCalls.get());
        assertThat(tools.timeCalls.get()).isZero();
        assertThat(tools.searchCalls.get()).isZero();
    }

    /** PoC 专用配置：@EnableAutoConfiguration 全量加载 Spring AI 自动配置（MiniMax/ChatClient/Tool/Retry 等），不扫主代码 @Component */
    @Configuration
    @EnableAutoConfiguration
    static class PoCConfig {
        @Bean
        PoCTools poCTools() {
            return new PoCTools();
        }
    }

    /** PoC 模拟工具集（含计数器，便于断言 LLM 是否调用） */
    static class PoCTools {
        final AtomicInteger timeCalls = new AtomicInteger();
        final AtomicInteger searchCalls = new AtomicInteger();
        final List<String> searchedProducts = new java.util.concurrent.CopyOnWriteArrayList<>();

        void reset() {
            timeCalls.set(0);
            searchCalls.set(0);
            searchedProducts.clear();
        }

        @Tool(description = "查询服务器当前时间。当用户问'几点'、'时间'、'日期'时使用此工具。")
        public String getServerTime() {
            timeCalls.incrementAndGet();
            return "2026-07-03 14:30:00 (UTC+8)";
        }

        @Tool(description = "在企业产品知识库中检索指定产品的信息（价格、规格等）。参数 productName 为产品名称，例如 '产品A' 或 '产品B'。当问题涉及产品价格、规格时使用。")
        public String searchProduct(String productName) {
            searchCalls.incrementAndGet();
            searchedProducts.add(productName);
            if (productName.contains("A")) return "产品A：价格 ¥2999，规格 4GB内存/128GB存储";
            if (productName.contains("B")) return "产品B：价格 ¥4599，规格 8GB内存/256GB存储";
            return "未找到产品：" + productName;
        }
    }
}
