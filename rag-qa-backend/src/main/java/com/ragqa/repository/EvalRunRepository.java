package com.ragqa.repository;

import com.ragqa.model.EvalRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface EvalRunRepository extends JpaRepository<EvalRun, String> {
    List<EvalRun> findByKbIdOrderByStartedAtDesc(String kbId);
}