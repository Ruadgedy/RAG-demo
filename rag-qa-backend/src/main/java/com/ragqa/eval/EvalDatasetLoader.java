package com.ragqa.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.InputStream;

/**
 * 黄金数据集加载器
 *
 * 支持两种位置：
 *   - classpath:docs/eval/golden-default.json   （项目自带，跟随代码版本）
 *   - file:/path/to/local.json                  （运营自定义，独立于代码）
 *
 * 【2026-06-29 新增 P2-01】
 */
@Component
@Slf4j
public class EvalDatasetLoader {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 按名称加载数据集
     *
     * 解析规则：
     *   1. 以 "classpath:" 开头 → 从 classpath 找
     *   2. 以 "file:" 开头 → 从文件系统找
     *   3. 否则先查 classpath，再查文件系统
     *
     * @param name 数据集名（可带或不带 .json 后缀）
     * @return EvalDataset
     * @throws Exception 找不到或解析失败时
     */
    public EvalDataset load(String name) throws Exception {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("数据集名称不能为空");
        }
        String normalized = name.endsWith(".json") ? name : name + ".json";

        Resource resource;
        if (normalized.startsWith("classpath:")) {
            resource = new ClassPathResource(normalized.substring("classpath:".length()));
        } else if (normalized.startsWith("file:")) {
            resource = new FileSystemResource(normalized.substring("file:".length()));
        } else {
            // 默认：先 classpath，再 file system
            Resource cp = new ClassPathResource("docs/eval/" + normalized);
            if (cp.exists()) {
                resource = cp;
            } else {
                resource = new FileSystemResource(normalized);
            }
        }

        if (!resource.exists()) {
            throw new IllegalArgumentException("数据集文件不存在: " + normalized);
        }

        try (InputStream in = resource.getInputStream()) {
            EvalDataset dataset = objectMapper.readValue(in, EvalDataset.class);
            if (dataset.getItems() == null || dataset.getItems().isEmpty()) {
                throw new IllegalArgumentException("数据集 items 为空: " + normalized);
            }
            log.info("加载黄金数据集: name={}, items={}, kbId={}",
                    dataset.getName(), dataset.getItems().size(), dataset.getKbId());
            return dataset;
        }
    }
}