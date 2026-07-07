package com.ragqa.eval;

import com.ragqa.agent.AgenticRagService;
import com.ragqa.eval.EvalService.AbCompareResult;
import com.ragqa.eval.EvalService.ModeOutcome;
import com.ragqa.service.RagService;
import com.ragqa.service.RagService.ChatResult;
import com.ragqa.service.RagService.RetrievalResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * {@link EvalService#abCompare} 单元测试（F22 A/B 对比）。
 *
 * <p>覆盖：双模式都成功 / agentic 降级 / linear 异常吞噬。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EvalServiceAbCompareTest {

    @Mock
    EvalDatasetLoader datasetLoader;

    @Mock
    RetrievalEvaluator retrievalEvaluator;

    @Mock
    AnswerEvaluator answerEvaluator;

    @Mock
    RagService ragService;

    @Mock
    AgenticRagService agenticRagService;

    @Mock
    org.springframework.data.jpa.repository.JpaRepository<Object, ?> objectRepository;

    private EvalService evalService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        // EvalService 构造器上有 7 个依赖；这里偷懒用反射塞进必要字段（避开 mock JPA repository 全套）
        evalService = new EvalService(
                datasetLoader, retrievalEvaluator, answerEvaluator,
                ragService, agenticRagService, null, null, null);
    }

    @Test
    void abCompareShouldReturnBothOutcomesWhenBothSucceed() {
        UUID kbId = UUID.randomUUID();
        // linear: 拿到 1 chunk / 1 source，answer 不为空
        when(ragService.chat(eq("产品A价格"), eq(kbId), any(), anyInt()))
                .thenReturn(new ChatResult(
                        "传统回答：2999元", List.of(
                                new RetrievalResult("产品A 规格...", "doc1_0", 0.85, "产品手册.pdf")),
                        100L, "产品A价格", "linear", 0, false));
        // agentic: 跑了 2 轮（kb + web），未降级
        when(agenticRagService.chat(anyString(), eq("产品A价格"), eq(kbId), any(), anyInt()))
                .thenReturn(new ChatResult(
                        "智能体回答：2999元（KB+Web 验证）", List.of(
                                new RetrievalResult("产品A 规格...", "doc1_0", 0.85, "产品手册.pdf"),
                                new RetrievalResult("竞品X 4599元", "doc2_0", 0.75, "市场报告.pdf")),
                        2200L, "产品A价格", "agentic", 2, false));

        AbCompareResult result = evalService.abCompare("产品A价格", kbId, List.of(), 3);

        assertThat(result.question()).isEqualTo("产品A价格");
        assertThat(result.kbId()).isEqualTo(kbId.toString());
        // linear
        ModeOutcome lin = result.linear();
        assertThat(lin.mode()).isEqualTo("linear");
        assertThat(lin.answer()).contains("2999");
        assertThat(lin.error()).isNull();
        assertThat(lin.retrievedChunkCount()).isEqualTo(1);
        assertThat(lin.sourceCount()).isEqualTo(1);
        assertThat(lin.latencyMs()).isGreaterThanOrEqualTo(0);
        // agentic
        ModeOutcome ag = result.agentic();
        assertThat(ag.mode()).isEqualTo("agentic");
        assertThat(ag.answer()).contains("智能体");
        assertThat(ag.error()).isNull();
        assertThat(ag.retrievedChunkCount()).isEqualTo(2);
        assertThat(ag.sourceCount()).isEqualTo(2);
        assertThat(ag.agentRounds()).isEqualTo(2);
        assertThat(ag.degraded()).isFalse();
        // mock 调用瞬间，latency 可能为 0；只断言非负
        assertThat(ag.latencyMs()).isGreaterThanOrEqualTo(0);
        assertThat(lin.latencyMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void abCompareShouldMarkDegradedWhenAgenticFellBackToLinear() {
        UUID kbId = UUID.randomUUID();
        // linear: 正常
        when(ragService.chat(eq("q"), eq(kbId), any(), anyInt()))
                .thenReturn(new ChatResult("linear 回答", List.of(), 50L, "q", "linear", 0, false));
        // agentic: ChatResult 显示 agentic 触发但降级了
        when(agenticRagService.chat(anyString(), eq("q"), eq(kbId), any(), anyInt()))
                .thenReturn(new ChatResult("降级到的 linear 回答", List.of(), 80L, "q",
                        "agentic", 0, true));

        AbCompareResult result = evalService.abCompare("q", kbId, List.of(), 3);

        ModeOutcome ag = result.agentic();
        assertThat(ag.error()).isNull();
        assertThat(ag.degraded()).isTrue();           // 关键：标了降级
        assertThat(ag.agentRounds()).isZero();        // 关键：rounds=0（agentic 没真跑）
        assertThat(ag.mode()).isEqualTo("agentic");    // 模式仍是 agentic（区分 "触发什么" vs "跑了什么"）
        // answer 来自 RagService fallback，但仍能从对比报告解读：agentic 这次等同于 linear
        assertThat(ag.answer()).isEqualTo("降级到的 linear 回答");
    }

    @Test
    void abCompareShouldSwallowLinearExceptionAndStillRunAgentic() {
        UUID kbId = UUID.randomUUID();
        // linear: 抛异常
        when(ragService.chat(eq("q"), eq(kbId), any(), anyInt()))
                .thenThrow(new RuntimeException("Chroma 突然挂了"));
        // agentic: 仍然能跑
        when(agenticRagService.chat(anyString(), eq("q"), eq(kbId), any(), anyInt()))
                .thenReturn(new ChatResult("智能体回答", List.of(), 300L, "q",
                        "agentic", 1, false));

        AbCompareResult result = evalService.abCompare("q", kbId, List.of(), 3);

        // linear 失败：answer=null，error 填了
        ModeOutcome lin = result.linear();
        assertThat(lin.answer()).isNull();
        assertThat(lin.error()).contains("Chroma 突然挂了");
        assertThat(lin.mode()).isEqualTo("linear");
        // agentic 仍正常
        ModeOutcome ag = result.agentic();
        assertThat(ag.answer()).isEqualTo("智能体回答");
        assertThat(ag.error()).isNull();
    }
}
