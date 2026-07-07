package com.ragqa.agent.trace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Agent Trace 收集器（Agentic RAG F21）。
 *
 * <p>负责：trace 记录落库 + SSE agent_step 事件 JSON 生成。
 *
 * <p>SSE 事件格式：
 * <pre>
 * event: agent_step
 * data: {"round":1,"tool":"kb_search","status":"start","query":"..."}
 * event: agent_step
 * data: {"round":1,"tool":"kb_search","status":"done","durationMs":320,"hits":3}
 * </pre>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentTraceCollector {

    private final AgentTraceRepository traceRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 记录一轮 tool 调用。
     */
    public void record(String chatId, int round, String toolName,
                      Map<String, Object> toolArgs, String resultSummary, int durationMs, String status) {
        try {
            AgentTrace trace = new AgentTrace();
            trace.setChatId(chatId);
            trace.setRound(round);
            trace.setToolName(toolName);
            trace.setToolArgs(toolArgs == null ? null : objectMapper.writeValueAsString(toolArgs));
            trace.setResultSummary(truncate(resultSummary, 500));
            trace.setDurationMs(durationMs);
            trace.setStatus(status);
            traceRepository.save(trace);
            log.debug("[agent_trace] chatId={}, round={}, tool={}, status={}",
                    chatId, round, toolName, status);
        } catch (Exception e) {
            log.warn("[agent_trace] 落库失败: chatId={}, tool={}: {}", chatId, toolName, e.getMessage());
        }
    }

    /**
     * 生成 SSE data 行 JSON（供 ChatService 直接拼接 event: agent_step\r\ndata: ...\n\n）。
     */
    public String sseData(String chatId, int round, String toolName,
                        String status, Map<String, Object> extra) {
        try {
            var node = objectMapper.createObjectNode();
            node.put("chatId", chatId);
            node.put("round", round);
            node.put("tool", toolName);
            node.put("status", status);
            if (extra != null) {
                extra.forEach((k, v) -> {
                    if (v != null) node.put(k, v.toString());
                });
            }
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            return "{}";
        }
    }

    public List<AgentTrace> getTraces(String chatId) {
        return traceRepository.findByChatIdOrderByRound(chatId);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
