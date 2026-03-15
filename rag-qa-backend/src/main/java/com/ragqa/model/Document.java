package com.ragqa.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Entity
@Table(name = "document")
public class Document {
    
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    
    @Column(name = "knowledge_base_id", nullable = false)
    private UUID knowledgeBaseId;
    
    @Column(name = "file_name", nullable = false)
    private String fileName;
    
    @Column(name = "file_type")
    private String fileType;
    
    @Column(name = "file_path")
    private String filePath;
    
    @Enumerated(EnumType.STRING)
    private DocumentStatus status = DocumentStatus.PENDING;
    
    @Column(name = "chunk_count")
    private Integer chunkCount = 0;
    
    @Column(name = "uploaded_at")
    private LocalDateTime uploadedAt;
    
    @Column(name = "processed_at")
    private LocalDateTime processedAt;
    
    @PrePersist
    protected void onCreate() {
        uploadedAt = LocalDateTime.now();
    }
    
    public enum DocumentStatus {
        PENDING, PROCESSING, COMPLETED, FAILED
    }
}
