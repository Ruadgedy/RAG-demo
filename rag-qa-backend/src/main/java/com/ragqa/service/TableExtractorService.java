package com.ragqa.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 表格内容抽取服务
 *
 * 作用：从 Word (.docx) 和 Excel (.xlsx) 文档中提取表格内容
 *
 * 为什么需要表格抽取：
 * ┌─────────────────────────────────────────────────────────────────┐
 * │                    文档中的表格处理问题                          │
 * ├─────────────────────────────────────────────────────────────────┤
 * │                                                                 │
 * │   普通文本处理（会丢失表格结构）：                                 │
 * │   ┌─────────────┬─────────────┬─────────────┐                   │
 * │   │ 姓名        │  语文       │  数学       │                   │
 * │   ├─────────────┼─────────────┼─────────────┤  →  "姓名 语文 数学 │
 * │   │ 张三        │  90        │  85        │     张三 90 85     │
 * │   └─────────────┴─────────────┴─────────────┘     李四 92 88"   │
 * │                                                                 │
 * │   表格抽取处理（保留结构）：                                      │
 * │   ┌─────────────┬─────────────┬─────────────┐                   │
 * │   │ 姓名        │  语文       │  数学       │                   │
 * │   ├─────────────┼─────────────┼─────────────┤  → Markdown 格式： │
 * │   │ 张三        │  90        │  85        │     | 姓名 | 语文 | │
 * │   │ 李四        │  92        │  88        │     |------|-----| │
 * │   └─────────────┴─────────────┴─────────────┘     | 张三 | 90  | │
 * │                                                   | 李四 | 92  | │
 * └─────────────────────────────────────────────────────────────────┘
 *
 * 输出格式：
 * - Markdown 表格格式，便于 LLM 理解和回答涉及表格数据的问题
 * - 示例："张三的数学成绩是多少？" → 直接回答"85分"
 *
 * 支持的表格格式：
 * - Word (.docx)：通过 Apache POI 提取
 * - Excel (.xlsx)：通过 Apache POI 提取
 * - PDF：表格结构较复杂，由 Tika 尽量提取，Tess4J OCR 辅助
 *
 * 依赖：
 * - Apache POI（已通过 Tika 间接引入）
 * - 支持 XWPF（Word）和 XSSF（Excel）
 */
@Service
@Slf4j
public class TableExtractorService {

    /** 是否启用表格抽取 */
    @Value("${table.extraction.enabled:true}")
    private boolean extractionEnabled;

    /** 最小表格行数（小于此值不抽取） */
    private static final int MIN_TABLE_ROWS = 2;

    /** 最小表格列数 */
    private static final int MIN_TABLE_COLS = 2;

    /**
     * Word 表格信息
     */
    public static class TableInfo {
        private final int tableIndex;           // 表格在文档中的索引
        private final int rowCount;              // 行数
        private final int colCount;              // 列数
        private final String markdownTable;     // Markdown 格式的表格

        public TableInfo(int tableIndex, int rowCount, int colCount, String markdownTable) {
            this.tableIndex = tableIndex;
            this.rowCount = rowCount;
            this.colCount = colCount;
            this.markdownTable = markdownTable;
        }

        public int getTableIndex() { return tableIndex; }
        public int getRowCount() { return rowCount; }
        public int getColCount() { return colCount; }
        public String getMarkdownTable() { return markdownTable; }
    }

    /**
     * 从 Word 文档中提取所有表格
     *
     * @param filePath Word 文件路径
     * @return 表格列表（Markdown 格式）
     */
    public List<TableInfo> extractTablesFromWord(Path filePath) {
        List<TableInfo> tables = new ArrayList<>();

        if (!extractionEnabled) {
            log.debug("表格抽取已禁用");
            return tables;
        }

        try (InputStream fis = new FileInputStream(filePath.toFile());
             XWPFDocument document = new XWPFDocument(fis)) {

            List<XWPFTable> xwpfTables = document.getTables();
            log.info("Word 文档包含 {} 个表格", xwpfTables.size());

            int tableIndex = 0;
            for (XWPFTable table : xwpfTables) {
                TableInfo tableInfo = convertTableToMarkdown(table, tableIndex);
                if (tableInfo != null) {
                    tables.add(tableInfo);
                    log.debug("表格 {} 已转换为 Markdown，共 {} 行 {} 列",
                            tableIndex, tableInfo.rowCount, tableInfo.colCount);
                }
                tableIndex++;
            }

        } catch (IOException e) {
            log.error("Word 文档读取失败: {}", e.getMessage());
        }

        return tables;
    }

    /**
     * 将 Word 表格转换为 Markdown 格式
     *
     * 转换示例：
     * ┌─────────────┬─────────────┐
     * │ 姓名        │  语文       │
     * ├─────────────┼─────────────┤
     * │ 张三        │  90         │
     * └─────────────┴─────────────┘
     *
     * 转换为：
     * | 姓名 | 语文 |
     * |------|------|
     * | 张三 | 90  |
     */
    private TableInfo convertTableToMarkdown(XWPFTable table, int tableIndex) {
        List<XWPFTableRow> rows = table.getRows();

        // 过滤太小的表格
        if (rows.size() < MIN_TABLE_ROWS) {
            return null;
        }

        // 检查列数（使用第一行作为参考）
        XWPFTableRow headerRow = rows.get(0);
        int colCount = headerRow.getTableCells().size();
        if (colCount < MIN_TABLE_COLS) {
            return null;
        }

        StringBuilder md = new StringBuilder();

        // 构建 Markdown 表格
        for (int i = 0; i < rows.size(); i++) {
            XWPFTableRow row = rows.get(i);
            List<String> cells = row.getCellTextList();

            // 清理单元格内容（移除多余空白）
            List<String> cleanedCells = new ArrayList<>();
            for (String cell : cells) {
                cleanedCells.add(cleanCellText(cell));
            }

            // 添加行
            md.append("| ").append(String.join(" | ", cleanedCells)).append(" |\n");

            // 添加分隔行（第二行作为表头分隔）
            if (i == 0) {
                md.append("| ");
                for (int j = 0; j < cleanedCells.size(); j++) {
                    md.append("------");
                    if (j < cleanedCells.size() - 1) {
                        md.append("|");
                    }
                }
                md.append(" |\n");
            }
        }

        return new TableInfo(tableIndex, rows.size(), colCount, md.toString());
    }

    /**
     * 清理单元格文本
     *
     * 处理规则：
     * 1. 合并多个连续空白为单个空格
     * 2. 移除首尾空白
     * 3. 处理单元格中的换行（转为空格）
     *
     * @param text 原始单元格文本
     * @return 清理后的文本
     */
    private String cleanCellText(String text) {
        if (text == null) {
            return "";
        }
        // 换行转为空格
        text = text.replaceAll("\\r\\n|\\n|\\r", " ");
        // 合并多个空格为单个
        text = text.replaceAll("\\s+", " ");
        // 移除首尾空白
        return text.trim();
    }

    /**
     * 从文本中识别并标记表格内容
     *
     * 用于 Tika 提取的原始文本中的表格结构识别
     * Tika 提取的文本可能包含表格分隔符（如 |、+、-）
     *
     * @param text 原始文本
     * @return 处理后的文本（表格转为 Markdown 格式）
     */
    public String detectAndConvertTables(String text) {
        if (!extractionEnabled || text == null) {
            return text;
        }

        // 识别 Markdown 表格（| col1 | col2 |）
        if (text.contains("|")) {
            text = convertMarkdownLikeTables(text);
        }

        // 识别 ASCII 表格（+---+---+）
        if (text.contains("+---")) {
            text = convertAsciiTables(text);
        }

        return text;
    }

    /**
     * 转换类似 Markdown 的表格格式
     *
     * 处理 Tika 提取的表格文本，确保每行以 | 开头和结尾
     */
    private String convertMarkdownLikeTables(String text) {
        StringBuilder result = new StringBuilder();
        String[] lines = text.split("\\n");

        boolean inTable = false;
        int pipeCount = 0;

        for (String line : lines) {
            int currentPipeCount = countChar(line, '|');

            // 如果行中有足够的管道符，认为是表格行
            if (currentPipeCount >= 3) {
                if (!inTable) {
                    inTable = true;
                }

                // 检查是否是分隔行（|---|---|）
                if (isSeparatorLine(line)) {
                    continue; // 跳过 Markdown 分隔行
                }

                // 清理和格式化表格行
                line = formatMarkdownTableLine(line);
                result.append(line).append("\n");
                pipeCount = currentPipeCount;
            } else {
                if (inTable && pipeCount > 0) {
                    // 表格结束，添加空行分隔
                    result.append("\n");
                    inTable = false;
                }
                result.append(line).append("\n");
            }
        }

        return result.toString();
    }

    /**
     * 判断是否是 Markdown 分隔行
     *
     * 分隔行示例：|---|---| 或 |:---|:---| (带对齐)
     */
    private boolean isSeparatorLine(String line) {
        // 移除首尾 | 和空格
        line = line.replaceAll("^\\||\\|$", "").trim();
        // 检查是否全部是 - 和 : 以及空白
        return line.matches("[:\\-\\s]+");
    }

    /**
     * 格式化 Markdown 表格行
     */
    private String formatMarkdownTableLine(String line) {
        // 规范化：确保首尾有 |
        line = line.trim();
        if (!line.startsWith("|")) {
            line = "|" + line;
        }
        if (!line.endsWith("|")) {
            line = line + "|";
        }
        // 清理多余的空格
        line = line.replaceAll("\\|\\s*", "|");
        line = line.replaceAll("\\s*\\|", "|");
        return line;
    }

    /**
     * 转换 ASCII 艺术表格
     *
     * ASCII 表格示例：
     * +----+------+------+
     * |姓名 | 语文 | 数学 |
     * +----+------+------+
     * |张三 |  90  |  85  |
     * +----+------+------+
     */
    private String convertAsciiTables(String text) {
        StringBuilder result = new StringBuilder();
        String[] lines = text.split("\\n");

        for (String line : lines) {
            // 检查是否是 ASCII 表格线
            if (line.contains("+---")) {
                continue; // 跳过表格边框线
            }

            // 转换 | 分隔的单元格
            if (line.contains("|")) {
                // 提取单元格内容并转为 Markdown
                String mdLine = convertAsciiRowToMarkdown(line);
                result.append(mdLine).append("\n");
            } else {
                result.append(line).append("\n");
            }
        }

        return result.toString();
    }

    /**
     * 将 ASCII 表格行转为 Markdown
     *
     * |姓名 | 语文 | 数学 |  →  | 姓名 | 语文 | 数学 |
     */
    private String convertAsciiRowToMarkdown(String line) {
        // 移除首尾的 |
        line = line.replaceAll("^\\|+", "");
        line = line.replaceAll("\\|+$", "");

        // 按 | 分割
        String[] cells = line.split("\\|");

        // 清理每个单元格并重新拼接
        StringBuilder md = new StringBuilder("|");
        for (String cell : cells) {
            md.append(cleanCellText(cell)).append("|");
        }

        return md.toString();
    }

    /**
     * 统计字符串中指定字符出现的次数
     */
    private int countChar(String text, char c) {
        int count = 0;
        for (char ch : text.toCharArray()) {
            if (ch == c) count++;
        }
        return count;
    }

    /**
     * 检查表格抽取是否启用
     */
    public boolean isEnabled() {
        return extractionEnabled;
    }
}
