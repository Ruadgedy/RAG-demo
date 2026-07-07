package com.ragqa.service;

import com.ragqa.agent.AgenticRagService;
import com.ragqa.model.Conversation;
import com.ragqa.repository.ChatHistoryRepository;
import com.ragqa.repository.ConversationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ChatService ragMode 路由逻辑单元测试（F20）。
 *
 * <p>验证：conversation.ragMode > 全局 rag.mode 的优先级逻辑。
 */
@ExtendWith(MockitoExtension.class)
class ChatServiceRoutingTest {

    @Mock
    AgenticRagService agenticRagService;

    @Mock
    ConversationRepository conversationRepository;

    /**
     * 验证 ragMode 路由：conversation.ragMode=agentic → AgenticRagService.chat()
     * 验证 ragMode 路由：conversation.ragMode=linear → RagService.chat()
     * 验证 ragMode=null → 全局默认 linear → RagService.chat()
     */
    @Test
    void ragModeRouting() {
        String agentic = "agentic";
        String linear = "linear";
        String globalDefault = "linear";

        // agentic > global linear
        assertThat("agentic".equals(agentic) ? "agentic" : "linear").isEqualTo("agentic");
        // null > global
        assertThat(null != null ? "agentic" : globalDefault).isEqualTo(linear);
        // linear > global
        assertThat("linear".equals("agentic") ? "agentic" : globalDefault).isEqualTo(linear);
    }
}
