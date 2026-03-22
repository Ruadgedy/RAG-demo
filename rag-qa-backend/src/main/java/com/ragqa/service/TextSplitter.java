package com.ragqa.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class TextSplitter {

    @Value("${chunk.strategy:fixed}")
    private String chunkStrategy;

    @Value("${chunk.size:500}")
    private int CHUNK_SIZE;

    @Value("${chunk.overlap:50}")
    private int CHUNK_OVERLAP;

    private static final Pattern PARAGRAPH_PATTERN = Pattern.compile("(?m)^[\\s]*$");

    public List<String> split(String text) {
        if ("paragraph".equalsIgnoreCase(chunkStrategy)) {
            return splitByParagraph(text);
        }
        return splitByFixedSize(text, CHUNK_SIZE, CHUNK_OVERLAP);
    }

    public List<String> split(String text, int chunkSize, int overlap) {
        return splitByFixedSize(text, chunkSize, overlap);
    }

    /**
     * 按段落分块
     */
    public List<String> splitByParagraph(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return chunks;
        }

        String[] paragraphs = text.split("\\n\\n+");
        
        for (String paragraph : paragraphs) {
            paragraph = paragraph.trim();
            if (paragraph.isEmpty()) {
                continue;
            }
            
            if (paragraph.length() <= CHUNK_SIZE) {
                chunks.add(paragraph);
            } else {
                List<String> subChunks = splitByFixedSize(paragraph, CHUNK_SIZE, CHUNK_OVERLAP);
                chunks.addAll(subChunks);
            }
        }
        
        return chunks;
    }

    /**
     * 固定大小分块
     */
    public List<String> splitByFixedSize(String text, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return chunks;
        }

        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());
            String chunk = text.substring(start, end);
            chunks.add(chunk);
            
            start = start + (chunkSize - overlap);
            
            if (start >= text.length()) {
                break;
            }
        }
        return chunks;
    }

    public String getChunkStrategy() {
        return chunkStrategy;
    }
}
