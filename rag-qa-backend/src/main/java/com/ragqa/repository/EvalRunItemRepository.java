package com.ragqa.repository;

import com.ragqa.model.EvalRunItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface EvalRunItemRepository extends JpaRepository<EvalRunItem, Long> {
    List<EvalRunItem> findByRunId(String runId);
}