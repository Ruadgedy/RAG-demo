package com.ragqa.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragqa.dto.ChatMessageDto;
import com.ragqa.dto.ConversationDto;
import com.ragqa.dto.SourceRef;
import com.ragqa.model.ChatHistory;
import com.ragqa.model.Conversation;
import com.ragqa.model.User;
import com.ragqa.repository.ChatHistoryRepository;
import com.ragqa.repository.ConversationRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 对话组控制器（V6 新增）
 *
 * 【V6 2026-06-30】
 * 用于管理多轮对话的对话组：
 * - 创建/删除对话组
 * - 查询对话组列表和详情
 * - 获取对话组下的消息
 * - 更新滑动窗口大小
 * - 更新对话组标题
 */
@RestController
@RequestMapping("/api/conversations")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "对话组", description = "多轮对话管理接口")
public class ConversationController {

    private final ConversationRepository conversationRepository;
    private final ChatHistoryRepository chatHistoryRepository;
    private final ObjectMapper objectMapper;

    /**
     * 创建新对话组
     */
    @Operation(summary = "创建新对话组", description = "点击'新对话'时调用，返回新建的对话组")
    @PostMapping
    public ResponseEntity<ConversationDto> createConversation(
            @Parameter(description = "知识库ID") @RequestParam UUID knowledgeBaseId,
            @Parameter(description = "滑动窗口大小") @RequestParam(defaultValue = "3") Integer historyWindow) {

        String userId = getCurrentUserId();
        log.info("创建新对话组: userId={}, kbId={}, window={}", userId, knowledgeBaseId, historyWindow);

        Conversation conv = new Conversation();
        conv.setUserId(userId);
        conv.setKnowledgeBaseId(knowledgeBaseId.toString());
        conv.setHistoryWindow(historyWindow);
        // title 和 firstQuery 在第一轮问答后由大模型生成

        conv = conversationRepository.save(conv);

        return ResponseEntity.ok(toDto(conv));
    }

    /**
     * 获取对话组列表
     */
    @Operation(summary = "获取对话组列表", description = "用于前端历史列表展示，按更新时间倒序")
    @GetMapping
    public ResponseEntity<List<ConversationDto>> listConversations() {
        String userId = getCurrentUserId();
        log.debug("获取对话组列表: userId={}", userId);

        List<Conversation> convs = conversationRepository.findByUserIdOrderByUpdatedAtDesc(userId);

        List<ConversationDto> dtos = convs.stream()
                .map(this::toDto)
                .collect(Collectors.toList());

        return ResponseEntity.ok(dtos);
    }

    /**
     * 获取对话组详情
     */
    @Operation(summary = "获取对话组详情", description = "根据ID获取对话组信息")
    @GetMapping("/{id}")
    public ResponseEntity<ConversationDto> getConversation(
            @Parameter(description = "对话组ID") @PathVariable String id) {

        String userId = getCurrentUserId();
        Conversation conv = conversationRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new IllegalArgumentException("对话组不存在: " + id));

        return ResponseEntity.ok(toDto(conv));
    }

    /**
     * 获取对话组下的所有消息
     */
    @Operation(summary = "获取对话组消息", description = "获取对话组下所有问答记录")
    @GetMapping("/{id}/messages")
    public ResponseEntity<List<ChatMessageDto>> getMessages(
            @Parameter(description = "对话组ID") @PathVariable String id) {

        String userId = getCurrentUserId();

        // 验证权限
        conversationRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new IllegalArgumentException("对话组不存在: " + id));

        List<ChatHistory> history = chatHistoryRepository
                .findByConversationIdOrderByTurnIndexAsc(id);

        List<ChatMessageDto> messages = history.stream()
                .map(this::toMessageDto)
                .collect(Collectors.toList());

        return ResponseEntity.ok(messages);
    }

    /**
     * 删除对话组
     */
    @Operation(summary = "删除对话组", description = "删除对话组及其所有消息")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteConversation(
            @Parameter(description = "对话组ID") @PathVariable String id) {

        String userId = getCurrentUserId();

        // 验证权限并删除
        conversationRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new IllegalArgumentException("对话组不存在: " + id));

        // 删除所有消息
        chatHistoryRepository.deleteByConversationId(id);

        // 删除对话组
        conversationRepository.deleteById(id);

        return ResponseEntity.noContent().build();
    }

    /**
     * 更新滑动窗口大小
     */
    @Operation(summary = "更新滑动窗口", description = "调整注入 prompt 的历史轮数")
    @PatchMapping("/{id}/window")
    public ResponseEntity<ConversationDto> updateHistoryWindow(
            @Parameter(description = "对话组ID") @PathVariable String id,
            @RequestBody Map<String, Integer> body) {

        String userId = getCurrentUserId();
        int window = body.getOrDefault("historyWindow", 3);

        // 限制范围 1-10
        window = Math.max(1, Math.min(10, window));

        Conversation conv = conversationRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new IllegalArgumentException("对话组不存在: " + id));

        conv.setHistoryWindow(window);
        conv = conversationRepository.save(conv);

        return ResponseEntity.ok(toDto(conv));
    }

    /**
     * 更新对话组标题
     */
    @Operation(summary = "更新标题", description = "由大模型生成的第一轮对话摘要")
    @PatchMapping("/{id}/title")
    public ResponseEntity<ConversationDto> updateTitle(
            @Parameter(description = "对话组ID") @PathVariable String id,
            @RequestBody Map<String, String> body) {

        String userId = getCurrentUserId();
        String title = body.get("title");

        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("标题不能为空");
        }

        Conversation conv = conversationRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new IllegalArgumentException("对话组不存在: " + id));

        conv.setTitle(title);
        conv = conversationRepository.save(conv);

        return ResponseEntity.ok(toDto(conv));
    }

    /**
     * 更新对话的 RAG 模式（Agentic RAG F20）
     * linear = 传统 RAG 流水线；agentic = LLM 自主编排工具
     * 传 null 恢复全局默认值
     */
    @Operation(summary = "切换 RAG 模式", description = "per-conversation 切换传统/智能体模式；传 null 恢复全局默认值")
    @PatchMapping("/{id}/rag-mode")
    public ResponseEntity<ConversationDto> updateRagMode(
            @Parameter(description = "对话组ID") @PathVariable String id,
            @RequestBody Map<String, String> body) {

        String userId = getCurrentUserId();
        String ragMode = body.get("ragMode"); // null=恢复默认值，linear|agentic

        // 校验合法值
        if (ragMode != null && !ragMode.isBlank()
                && !ragMode.equals("linear") && !ragMode.equals("agentic")) {
            throw new IllegalArgumentException("ragMode 必须是 linear | agentic 或不传（恢复默认）");
        }

        Conversation conv = conversationRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new IllegalArgumentException("对话组不存在: " + id));

        conv.setRagMode(ragMode); // null=恢复全局默认值
        conv = conversationRepository.save(conv);
        log.info("[rag-mode] 切换: conversationId={}, ragMode={}", id, ragMode);

        return ResponseEntity.ok(toDto(conv));
    }

    // ==================== 私有方法 ====================

    private String getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof User user) {
            return user.getUsername();
        }
        return "unknown";
    }

    private ConversationDto toDto(Conversation conv) {
        ConversationDto dto = new ConversationDto();
        dto.setId(conv.getId());
        dto.setUserId(conv.getUserId());
        dto.setTitle(conv.getTitle());
        dto.setFirstQuery(conv.getFirstQuery());
        dto.setKnowledgeBaseId(conv.getKnowledgeBaseId());
        dto.setHistoryWindow(conv.getHistoryWindow());
        dto.setRagMode(conv.getRagMode());
        dto.setCreatedAt(conv.getCreatedAt());
        dto.setUpdatedAt(conv.getUpdatedAt());

        // 统计轮次
        long turnCount = chatHistoryRepository.countByConversationId(conv.getId());
        dto.setTurnCount((int) turnCount);

        // 用于历史列表展示的摘要
        dto.setSummary(conv.getFirstQuery() != null ? conv.getFirstQuery() :
                (conv.getTitle() != null ? conv.getTitle() : "新对话"));

        return dto;
    }

    private ChatMessageDto toMessageDto(ChatHistory h) {
        ChatMessageDto dto = new ChatMessageDto();
        dto.setChatId(h.getChatId());
        dto.setTurnIndex(h.getTurnIndex());
        dto.setQuery(h.getQuery());
        dto.setContent(h.getContent());
        dto.setCreatedAt(h.getCreatedAt() != null ? h.getCreatedAt().toString() : null);

        // 解析 ragMetadata
        dto.setRagMetadata(h.getRagMetadata());

        // 解析 chatMetadata 中的 sources
        if (h.getChatMetadata() != null) {
            try {
                Map<String, Object> meta = objectMapper.readValue(
                        h.getChatMetadata(), new TypeReference<Map<String, Object>>() {});
                @SuppressWarnings("unchecked")
                List<SourceRef> sources = objectMapper.convertValue(
                        meta.get("sources"), new TypeReference<List<SourceRef>>() {});
                dto.setSources(sources);
            } catch (JsonProcessingException e) {
                log.warn("解析 chatMetadata 失败: {}", e.getMessage());
            }
        }

        return dto;
    }
}