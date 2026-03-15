package com.ragqa.repository;

import com.ragqa.model.KnowledgeBase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBase, UUID> {
    
    Optional<KnowledgeBase> findByName(String name);
    
    boolean existsByName(String name);
}
