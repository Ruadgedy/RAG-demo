package com.ragqa.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 问答响应DTO
 *
 * 作用：封装非流式问答接口（POST /api/chat）的响应数据
 *
 * 字段说明：
 * - sessionId: 本次问答的会话ID，用于关联同一轮对话的所有消息
 *   （user 问题与 assistant 回答共享同一 sessionId，已由后端落库到 chat_history）
 * - answer: LLM 基于知识库生成的完整回答
 * - sources: 【2026-06-29 增量 P0-01】本次回答引用的文档来源列表
 *   让前端能展示「参考 3 篇文档 ▾」可展开卡片，提升答案可信度
 *
 * 设计说明：
 * - 旧版本 /api/chat 仅返回纯文本 String，无法把 sessionId 带回前端，
 *   导致前端无法将当前问答归并到某个历史会话。
 * - 升级为对象响应后，前端可据此刷新侧边栏「聊天历史」并高亮当前会话。
 *
 * 注解说明：@Data + @AllArgsConstructor 会抑制 @Data 隐式无参构造器，
 * 显式加 @NoArgsConstructor 恢复，用于 Jackson 反序列化与测试构建对象
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatResponse {
    /** 本次问答的会话ID（同一轮 user/assistant 消息共享） */
    private String sessionId;

    /** LLM 完整回答 */
    private String answer;

    /**
     * 【2026-06-29 增量 P0-01】本次回答引用的文档来源列表
     *
     * 可能为空（空知识库 / 检索无结果 / LLM 直接回答等场景）。
     * 非空时按相关性降序排，与 ChatResponse.answer 中【文档X】的 X 编号一一对应。
     *
     * 前端渲染建议：
     *   <template v-if="msg.sources?.length">
     *     <details>
     *       <summary>参考 {{ msg.sources.length }} 篇文档</summary>
     *       <div v-for="(src, i) in msg.sources">
     *         【{{ i+1 }}】{{ src.fileName }} (片段 {{ src.chunkIndex }})
     *       </div>
     *     </details>
     *   </template>
     */
    private List<SourceRef> sources;
}