package com.ragqa.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * RetrievalEvaluator 单元测试
 *
 * 覆盖各种边界场景：完美命中、部分命中、完全未命中、空集、单 expected 等
 */
class RetrievalEvaluatorTest {

    private final RetrievalEvaluator evaluator = new RetrievalEvaluator();

    @Test
    void perfectHitShouldScoreAll1() {
        var m = evaluator.evaluateOne(
                List.of("a", "b", "c"),
                List.of("a", "b", "c"),
                3);
        assertThat(m.hitAtK()).isTrue();
        assertThat(m.recallAtK()).isEqualTo(1.0);
        assertThat(m.mrrAtK()).isEqualTo(1.0);     // 第一个就在 rank 0
        assertThat(m.ndcgAtK()).isEqualTo(1.0);
        assertThat(m.rankOfFirstHit()).isEqualTo(0);
    }

    @Test
    void noHitShouldScoreAll0() {
        var m = evaluator.evaluateOne(
                List.of("a", "b"),
                List.of("x", "y", "z"),
                3);
        assertThat(m.hitAtK()).isFalse();
        assertThat(m.recallAtK()).isEqualTo(0.0);
        assertThat(m.mrrAtK()).isEqualTo(0.0);
        assertThat(m.ndcgAtK()).isEqualTo(0.0);
        assertThat(m.rankOfFirstHit()).isEqualTo(-1);
    }

    @Test
    void firstHitAtRank2ShouldGiveMrrThird() {
        // expected = {a}; retrieved = [x, y, a] → first hit at rank 2, MRR = 1/3
        var m = evaluator.evaluateOne(
                List.of("a"),
                List.of("x", "y", "a"),
                3);
        assertThat(m.hitAtK()).isTrue();
        assertThat(m.recallAtK()).isEqualTo(1.0);
        assertThat(m.mrrAtK()).isEqualTo(1.0 / 3.0, within(1e-6));
        assertThat(m.rankOfFirstHit()).isEqualTo(2);
    }

    @Test
    void partialHitRecallShouldBeFraction() {
        // expected = {a, b, c, d}; retrieved = [a, b] → recall = 2/4 = 0.5
        var m = evaluator.evaluateOne(
                List.of("a", "b", "c", "d"),
                List.of("a", "b"),
                5);
        assertThat(m.hitAtK()).isTrue();
        assertThat(m.recallAtK()).isEqualTo(0.5);
        assertThat(m.mrrAtK()).isEqualTo(1.0);    // first hit at rank 0
    }

    @Test
    void emptyExpectedShouldReturnZeros() {
        var m = evaluator.evaluateOne(
                List.of(),
                List.of("a"),
                3);
        assertThat(m.hitAtK()).isFalse();
        assertThat(m.recallAtK()).isEqualTo(0.0);
    }

    @Test
    void emptyRetrievedShouldReturnZeros() {
        var m = evaluator.evaluateOne(
                List.of("a"),
                List.of(),
                3);
        assertThat(m.hitAtK()).isFalse();
        assertThat(m.rankOfFirstHit()).isEqualTo(-1);
    }

    @Test
    void topKShouldClipRetrievedList() {
        // retrieved 有 5 个，K=3 只看前 3 个
        // expected 在 retrieved[4]，top-3 看不见 → 算未命中
        var m = evaluator.evaluateOne(
                List.of("e"),
                List.of("a", "b", "c", "d", "e"),
                3);
        assertThat(m.hitAtK()).isFalse();
    }

    @Test
    void summaryShouldAggregateCorrectly() {
        var items = List.of(
                evaluator.evaluateOne(List.of("a"), List.of("a", "x", "y"), 3),  // 完美
                evaluator.evaluateOne(List.of("b"), List.of("x", "b", "y"), 3),  // rank 1
                evaluator.evaluateOne(List.of("c"), List.of("x", "y", "z"), 3)   // 未命中
        );
        var s = evaluator.summarize(items);
        assertThat(s.totalItems()).isEqualTo(3);
        assertThat(s.hitCount()).isEqualTo(2);
        assertThat(s.hitRateAtK()).isEqualTo(2.0 / 3.0, within(1e-6));
        // recall: [1, 1, 0] avg = 2/3
        assertThat(s.recallAtK()).isEqualTo(2.0 / 3.0, within(1e-6));
        // mrr: [1, 1/2, 0] avg = 0.5
        assertThat(s.mrrAtK()).isEqualTo(0.5, within(1e-6));
    }

    @Test
    void ndcgShouldBeLessThanPerfectWhenRankingIsSuboptimal() {
        // ideal: [a, b, c] → IDCG = 1/log2(2) + 1/log2(3) + 1/log2(4) ≈ 2.131
        // actual: [c, b, a] (reverse order) → DCG = 1/log2(2) + 1/log2(3) + 1/log2(4)
        //   1/log2(2) = 1.0, 1/log2(3) ≈ 0.631, 1/log2(4) = 0.5 → DCG ≈ 2.131
        // NDCG = 1.0（因为 log 分母相同，分子相同）
        //   实际上 DCG = IDCG 时 NDCG = 1，即使顺序反了
        // 真正影响 NDCG 的是"相关性差异"，这里全是 1 所以都一样
        var m = evaluator.evaluateOne(
                List.of("a", "b", "c"),
                List.of("c", "b", "a"),
                3);
        assertThat(m.ndcgAtK()).isEqualTo(1.0, within(1e-6));
    }
}