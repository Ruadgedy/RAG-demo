package com.ragqa.agent.trace;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * AgentTrace 查询接口（Agentic RAG F21）。
 */
@Repository
public interface AgentTraceRepository extends JpaRepository<AgentTrace, Long> {

    List<AgentTrace> findByChatIdOrderByRound(String chatId);
}
