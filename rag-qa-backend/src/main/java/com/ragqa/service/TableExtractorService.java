package com.ragqa.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * ============================================================
 * 表格内容抽取服务
 * ============================================================
 *
 * 【功能说明】
 * 从 Word (.docx) 和 Excel (.xlsx) 文档中提取表格内容，
 * 并转换为 Markdown 格式，便于 LLM 理解和问答。
 *
 * 【为什么需要表格抽取】
 *
 * ┌─────────────────────────────────────────────────────────────────┐
 * │                    普通文本处理的局限性                            │
 * ├─────────────────────────────────────────────────────────────────┤
 * │                                                                 │
 * │   普通文本提取（会丢失表格结构）：                                  │
 * │                                                                 │
 * │   原始表格：                                                      │
 * │   ┌─────────────┬─────────────┬─────────────┐                   │
 * │   │ 姓名        │  语文       │  数学       │                   │
 * │   ├─────────────┼─────────────┼─────────────┤                   │
 * │   │ 张三        │  90        │  85        │                   │
 * │   └─────────────┴─────────────┴─────────────┘                   │
 * │                                                                 │
 * │   普通提取结果（丢失结构）：                                        │
 * │   "姓名 语文 数学 张三 90 85 李四 92 88"                        │
 * │                                                                 │
 * │   ❌ 问题：无法知道张三的语文成绩是90还是85                       │
 * │   ❌ 问题：表格行列关系丢失                                      │
 * │                                                                 │
 * └─────────────────────────────────────────────────────────────────┘
 *
 * ┌─────────────────────────────────────────────────────────────────┐
 * │                    表格抽取后的结果                               │
 * ├─────────────────────────────────────────────────────────────────┤
 * │                                                                 │
 * │   Markdown 表格：                                                │
 * │   | 姓名 | 语文 | 数学 |                                         │
 * │   |------|------|------|                                        │
 * │   | 张三 | 90   | 85   |                                        │
 * │   | 李四 | 92   | 88   |                                        │
 * │                                                                 │
 * │   ✅ 行列关系完整保留                                            │
 * │   ✅ LLM 可以理解表格结构                                        │
 * │   ✅ 可以回答"张三的数学成绩是多少"                               │
 * │                                                                 │
 * └─────────────────────────────────────────────────────────────────┘
 *
 * 【支持的表格格式】
 * - Word (.docx)：通过 Apache POI 提取
 * - Excel (.xlsx)：通过 Apache POI 提取
 * - PDF：表格结构较复杂，由 Tika 尽量提取
 *
 * 【输出格式】
 * Markdown 表格格式：
 * | 列1 | 列2 | 列3 |
 * |------|------|------|
 * | 内容 | 内容 | 内容 |
 *
 * 【依赖】
 * - Apache POI（已通过 Tika 间接引入）
 * - 支持 XWPF（Word）和 XSSF（Excel）
 */
@Service
@Slf4j
public class TableExtractorService {

    // ============================================================
    // 配置
    // ============================================================

    /** 是否启用表格抽取 */
    @Value("${table.extraction.enabled:true}")
    private boolean extractionEnabled;

    /** 最小表格行数（小于此值不抽取） */
    // 【说明】太小的表格可能只是简单列表，不值得抽取
    private static final int MIN_TABLE_ROWS = 2;

    /** 最小表格列数 */
    private static final int MIN_TABLE_COLS = 2;

    // ============================================================
    // 数据结构
    // ============================================================

    /**
     * 表格信息
     *
     * 【说明】封装提取出的表格的完整信息
     */
    public static class TableInfo {
        /** 表格在文档中的索引（从0开始） */
        private final int tableIndex;
        /** 行数 */
        private final int rowCount;
        /** 列数 */
        private final int colCount;
        /** Markdown 格式的表格内容 */
        private final String markdownTable;

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

    // ============================================================
    // 核心方法
    // ============================================================

    /**
     * 从 Word 文档中提取所有表格
     *
     * 【支持的格式】
     * - .docx（Word 2007+）
     *
     * 【处理流程】
     * 1. 加载 Word 文档
     * 2. 遍历文档中的所有表格
     * 3. 将每个表格转换为 Markdown 格式
     *
     * @param filePath Word 文件路径
     * @return 表格列表（Markdown 格式）
     */
    public List<TableInfo> extractTablesFromWord(Path filePath) {
        List<TableInfo> tables = new ArrayList<>();

        // 表格抽取被禁用
        if (!extractionEnabled) {
            log.debug("表格抽取已禁用");
            return tables;
        }

        try (InputStream fis = new FileInputStream(filePath.toFile());
             XWPFDocument document = new XWPFDocument(fis)) {

            // 获取文档中的所有表格
            // 【注意】这只会获取文档正文的表格，不会获取页眉页脚的表格
            List<XWPFTable> xwpfTables = document.getTables();
            log.info("Word 文档包含 {} 个表格", xwpfTables.size());

            int tableIndex = 0;
            for (XWPFTable table : xwpfTables) {
                // 转换为 Markdown 格式
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
     * 【转换示例】
     *
     * 原始 Word 表格：
     * ┌─────────────┬─────────────┬─────────────┐
     * │ 姓名        │  语文       │  数学       │
     * ├─────────────┼─────────────┼─────────────┤
     * │ 张三        │  90         │  85         │
     * └─────────────┴─────────────┴─────────────┘
     *
     * 转换为 Markdown：
     * | 姓名 | 语文 | 数学 |
     * |------|------|------|
     * | 张三 | 90   | 85   |
     *
     * 【过滤规则】
     * - 行数 < 2：不是真正的表格
     * - 列数 < 2：不是真正的表格
     *
     * @param table Word 表格对象
     * @param tableIndex 表格索引
     * @return 表格信息，如果不满足条件返回 null
     */
    private TableInfo convertTableToMarkdown(XWPFTable table, int tableIndex) {
        List<XWPFTableRow> rows = table.getRows();

        // 过滤太小的表格
        if (rows.size() < MIN_TABLE_ROWS) {
            return null;
        }

        // 获取列数（使用第一行作为参考）
        XWPFTableRow headerRow = rows.get(0);
        int colCount = headerRow.getTableCells().size();
        if (colCount < MIN_TABLE_COLS) {
            return null;
        }

        StringBuilder md = new StringBuilder();

        // 遍历每一行
        for (int i = 0; i < rows.size(); i++) {
            XWPFTableRow row = rows.get(i);
            // 获取单元格文本列表
            List<XWPFTableCell> cellList = row.getTableCells();
            List<String> cleanedCells = new ArrayList<>();
            for (XWPFTableCell cell : cellList) {
                cleanedCells.add(cleanCellText(cell.getText()));
            }

            // 构建 Markdown 行
            md.append("| ").append(String.join(" | ", cleanedCells)).append(" |\n");

            // 在表头后添加分隔行
            // 【说明】第二行（索引1）添加 |---|---| 形式的分隔线
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
     * 【处理规则】
     * 1. 换行符转为空格（单元格内换行通常不是表格结构）
     * 2. 多个空白合并为单个空格
     * 3. 移除首尾空白
     *
     * 【示例】
     * 输入: "  张\n\n三  "  →  输出: "张三"
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
     * 从文本中识别并转换表格
     *
     * 【用途】
     * 处理 Tika 提取的原始文本中的表格结构
     *
     * 【支持的格式】
     * 1. Markdown 表格：| col1 | col2 |
     * 2. ASCII 表格：+----+-----+
     *
     * @param text 原始文本
     * @return 处理后的文本（表格转为 Markdown）
     */
    public String detectAndConvertTables(String text) {
        if (!extractionEnabled || text == null) {
            return text;
        }

        // 识别 Markdown 表格
        if (text.contains("|")) {
            text = convertMarkdownLikeTables(text);
        }

        // 识别 ASCII 表格
        if (text.contains("+---")) {
            text = convertAsciiTables(text);
        }

        return text;
    }

    /**
     * 转换 Markdown 风格的表格
     *
     * 【识别规则】
     * - 行中包含 3 个或更多 | 字符
     *
     * 【处理逻辑】
     * 1. 识别表格行
     * 2. 跳过 Markdown 分隔行（|---|）
     * 3. 规范化表格格式
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

                // 跳过 Markdown 分隔行
                if (isSeparatorLine(line)) {
                    continue;
                }

                // 格式化并添加行
                line = formatMarkdownTableLine(line);
                result.append(line).append("\n");
                pipeCount = currentPipeCount;
            } else {
                // 非表格行
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
     * 【分隔行示例】
     * |---|---|---|
     * |:---|:---|:---|
     * |:---|---\ |:---|
     */
    private boolean isSeparatorLine(String line) {
        // 移除首尾 | 和空格
        line = line.replaceAll("^\\||\\|$", "").trim();
        // 检查是否全部是 - 和 : 以及空白
        return line.matches("[:\\-\\s]+");
    }

    /**
     * 格式化 Markdown 表格行
     *
     * 【规范化操作】
     * 1. 确保首尾有 |
     * 2. 单元格之间的空格标准化
     */
    private String formatMarkdownTableLine(String line) {
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
     * 【ASCII 表格示例】
     * +----+------+------+
     * |姓名 | 语文 | 数学 |
     * +----+------+------+
     * |张三 |  90  |  85  |
     * +----+------+------+
     *
     * 【转换规则】
     * 1. 跳过边框行（+---+）
     * 2. 将 | 分隔的单元格转为 Markdown 格式
     */
    private String convertAsciiTables(String text) {
        StringBuilder result = new StringBuilder();
        String[] lines = text.split("\\n");

        for (String line : lines) {
            // 跳过 ASCII 边框行
            if (line.contains("+---")) {
                continue;
            }

            // 转换 | 分隔的单元格
            if (line.contains("|")) {
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
     * 【示例】
     * 输入:  |姓名 | 语文 | 数学 |
     * 输出:  | 姓名 | 语文 | 数学 |
     */
    private String convertAsciiRowToMarkdown(String line) {
        // 移除首尾的 |
        line = line.replaceAll("^\\|+", "");
        line = line.replaceAll("\\|+$", "");

        // 按 | 分割
        String[] cells = line.split("\\|");

        // 清理并重新拼接
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
