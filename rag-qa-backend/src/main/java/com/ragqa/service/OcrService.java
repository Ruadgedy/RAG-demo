package com.ragqa.service;

import lombok.extern.slf4j.Slf4j;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * ============================================================
 * OCR 光学字符识别服务
 * ============================================================
 *
 * 【功能说明】
 * 从图片型 PDF 或扫描版 PDF 中提取文字内容。
 *
 * 【应用场景】
 * 1. 扫描版 PDF（纸质文档电子化后的 PDF）
 * 2. 图片型 PDF（直接从图片转换的 PDF）
 * 3. 含有水印、印章等的 PDF
 *
 * 【为什么需要 OCR】
 * ┌─────────────────────────────────────────────────────────────────┐
 * │                    PDF 文档类型                                   │
 * ├─────────────────────────────────────────────────────────────────┤
 * │                                                                 │
 * │   【可搜索型 PDF（Searchable PDF）】                              │
 * │   ├── 有文字层：可以直接选中、复制文字                              │
 * │   ├── 可以用 Ctrl+F 搜索                                        │
 * │   └── 处理方式：用 Tika 直接提取文字即可                          │
 * │                                                                 │
 * │   【扫描版 PDF（Scanned PDF）】                                  │
 * │   ├── 没有文字层：每一页是一张扫描图片                            │
 * │   ├── 无法选中、复制文字                                         │
 * │   └── 处理方式：必须用 OCR 识别文字                              │
 * │                                                                 │
 * │   【图片型 PDF（Image PDF）】                                    │
 * │   ├── 整页是一张图片                                             │
 * │   ├── 常见于：从图片直接转换的 PDF                               │
 * │   └── 处理方式：必须 OCR                                          │
 * │                                                                 │
 * └─────────────────────────────────────────────────────────────────┘
 *
 * 【OCR 工作流程】
 *
 *     ┌─────────────────────┐
 *     │   加载 PDF 文件      │
 *     └──────────┬──────────┘
 *                ▼
 *     ┌─────────────────────┐
 *     │ 尝试提取文字层       │ ──有文字且足够──▶ 直接使用
 *     └──────────┬──────────┘
 *                │ 文字为空/过少
 *                ▼
 *     ┌─────────────────────┐
 *     │ 将 PDF 转为图片      │  (PDFRenderer)
 *     └──────────┬──────────┘
 *                ▼
 *     ┌─────────────────────┐
 *     │  Tesseract OCR      │  每页独立识别
 *     │  识别文字            │
 *     └──────────┬──────────┘
 *                ▼
 *     ┌─────────────────────┐
 *     │ 合并所有页结果        │
 *     └──────────┬──────────┘
 *                ▼
 *     ┌─────────────────────┐
 *     │   返回完整文本       │
 *     └─────────────────────┘
 *
 * 【Tesseract 语言包】
 * - eng: 英文
 * - chi_sim: 简体中文
 * - chi_tra: 繁体中文
 * - jpn: 日语
 * - kor: 韩语
 *
 * 【macOS 安装方法】
 * ```bash
 * # 安装 Tesseract
 * brew install tesseract
 *
 * # 安装所有语言包（包含中文）
 * brew install tesseract-lang
 *
 * # 或单独安装需要的语言
 * brew install tesseract --with-chi-sim  # 中文简体
 * ```
 *
 * 【环境变量配置】
 * ```bash
 * export TESSDATA_PREFIX=/usr/local/share/tessdata
 * ```
 */
@Service
@Slf4j
public class OcrService {

    // ============================================================
    // 配置
    // ============================================================

    /**
     * Tesseract 实例
     *
     * 【注意】Tesseract 不是线程安全的
     * 使用 synchronized 保证线程安全
     */
    private Tesseract tesseract;

    /** 是否启用 OCR */
    // 【说明】设为 false 时，跳过 OCR 步骤
    @Value("${ocr.enabled:true}")
    private boolean ocrEnabled;

    /** OCR 语言包 */
    // 【说明】多个语言用 + 连接，如 chi_sim+eng
    @Value("${ocr.language:chi_sim+eng}")
    private String ocrLanguage;

    /** 页面分割模式 (PSM) */
    // 【说明】控制 Tesseract 如何分割页面
    @Value("${ocr.psm:3}")
    private int psm;

    // ============================================================
    // 核心方法
    // ============================================================

    /**
     * 初始化 Tesseract OCR 引擎
     *
     * 【调用时机】第一次使用时懒加载
     *
     * 【初始化内容】
     * 1. 创建 Tesseract 实例
     * 2. 设置语言包路径（TESSDATA_PREFIX）
     * 3. 设置语言包名称
     * 4. 设置页面分割模式
     */
    private synchronized void initTesseract() {
        // 已初始化，跳过
        if (tesseract != null) {
            return;
        }

        try {
            tesseract = new Tesseract();

            // 设置语言包路径
            // Tesseract 会在这个目录下查找 .traineddata 文件
            // 例如：chi_sim.traineddata -> /usr/local/share/tessdata/chi_sim.traineddata
            String tessDataPath = System.getenv("TESSDATA_PREFIX");
            if (tessDataPath != null && !tessDataPath.isEmpty()) {
                tesseract.setDatapath(tessDataPath);
                log.info("OCR 使用语言包路径: {}", tessDataPath);
            } else {
                // 尝试常见路径
                String[] commonPaths = {
                        // 替换为本地tessdata存储地址
                        "/opt/homebrew/Cellar/tesseract/5.5.2/share/tessdata",
                    "/usr/local/share/tessdata",
                    "/usr/share/tessdata",
                    "/opt/homebrew/share/tessdata",
                    "/usr/local/Cellar/tesseract/5.3.1_1/share/tessdata"
                };
                for (String path : commonPaths) {
                    File f = new File(path);
                    if (f.exists() && f.isDirectory()) {
                        tesseract.setDatapath(path);
                        log.info("OCR 找到语言包路径: {}", path);
                        break;
                    }
                }
            }

            // 设置语言包
            tesseract.setLanguage(ocrLanguage);
            log.info("OCR 加载语言包: {}", ocrLanguage);

            // 设置页面分割模式
            // PSM 模式说明：
            // 0 = 方向检测和脚本检测 (OSD) only
            // 1 = 使用 OSD 自动分页
            // 3 = 全自动分页 ← 常用
            // 4 = 假设一列可变大小的文本
            // 6 = 假设一个统一块文本
            // 11 = 稀疏文本，按任意顺序
            // 12 = OCR 作为单行
            tesseract.setPageSegMode(psm);

        } catch (Exception e) {
            log.error("OCR 初始化失败: {}", e.getMessage());
            tesseract = null;
        }
    }

    /**
     * 从 PDF 中提取文字（智能模式）
     *
     * 【推荐入口方法】
     * 自动判断 PDF 类型，选择最优提取方式：
     * 1. 先尝试直接提取文字层（高效）
     * 2. 如果文字层为空或过少，启用 OCR（精准但慢）
     *
     * 【判断逻辑】
     * - 如果提取的文字长度 < 50 字符，认为是扫描版 PDF
     * - 这种判断简单有效，50 字符对于正常文档来说太少了
     *
     * @param filePath PDF 文件路径
     * @return 提取的文字内容，如果失败返回 null
     */
    public String extractTextFromPdf(Path filePath) {
        // OCR 被禁用
        if (!ocrEnabled) {
            log.info("OCR 已禁用");
            return null;
        }

        try {
            // Step 1: 尝试直接提取文字层
            // 使用 PDFBox 的 PDFTextStripper 直接提取
            // 这种方式快速高效，适合有文字层的 PDF
            String directText = extractTextLayer(filePath);
            log.info("PDF 文字层提取结果长度: {}", directText.length());

            // Step 2: 判断是否需要 OCR
            // 【阈值说明】
            // - 50 字符是一个合理的阈值
            // - 正常一页文档不可能只有 50 个字符
            // - 如果只有这么少，说明是扫描版 PDF
            if (directText.trim().length() < 50) {
                log.info("PDF 文字层内容过少（{} 字符），启用 OCR",
                        directText.trim().length());

                // 执行 OCR
                String ocrText = extractTextByOcr(filePath);

                // 合并结果
                // 【合并策略】
                // - 如果文字层完全为空：直接用 OCR 结果
                // - 如果文字层有内容：文字层 + OCR 结果（可能是混合 PDF）
                if (ocrText != null && !ocrText.isEmpty()) {
                    if (directText.trim().isEmpty()) {
                        return ocrText;
                    } else {
                        return directText + "\n\n[以下内容来自 OCR 识别]\n" + ocrText;
                    }
                }
            }

            return directText;

        } catch (Exception e) {
            log.error("PDF 文字提取失败: {}", e.getMessage());
            // OCR 作为备用方案
            if (tesseract != null) {
                return extractTextByOcr(filePath);
            }
            return null;
        }
    }

    /**
     * 直接从 PDF 提取文字层
     *
     * 【使用技术】Apache PDFBox
     *
     * 【适用场景】
     * - 可搜索型 PDF
     * - 有文字层的 PDF
     *
     * 【优点】
     * - 速度快（比 OCR 快几十倍）
     * - 准确率高（文字层是原始文本）
     *
     * @param filePath PDF 文件路径
     * @return 提取的文字内容
     */
    private String extractTextLayer(Path filePath) throws IOException {
        try (PDDocument document = PDDocument.load(filePath.toFile())) {
            // PDFTextStripper 会按页面顺序提取所有文字
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document);
        }
    }

    /**
     * 使用 OCR 从 PDF 中提取文字
     *
     * 【工作流程】
     * 1. 加载 PDF 文档
     * 2. 遍历每一页
     * 3. 将页面渲染为图片（300 DPI）
     * 4. 对图片执行 OCR 识别
     * 5. 合并所有页的识别结果
     *
     * 【性能考虑】
     * - 每页独立处理，避免内存溢出
     * - DPI 设置为 300，平衡速度和准确率
     * - 临时文件及时清理
     *
     * @param filePath PDF 文件路径
     * @return OCR 识别的文字内容
     */
    private String extractTextByOcr(Path filePath) {
        // 确保 Tesseract 已初始化
        initTesseract();

        if (tesseract == null) {
            log.error("Tesseract 未初始化，OCR 不可用");
            return null;
        }

        List<String> pageTexts = new ArrayList<>();
        int totalPages = 0;

        try (PDDocument document = PDDocument.load(filePath.toFile())) {
            totalPages = document.getNumberOfPages();
            log.info("PDF 共 {} 页，开始 OCR 识别", totalPages);

            // PDFRenderer 用于将 PDF 页面渲染为图片
            PDFRenderer renderer = new PDFRenderer(document);

            for (int pageNum = 0; pageNum < totalPages; pageNum++) {
                try {
                    // Step 1: 渲染当前页为图片
                    // renderImage(pageIndex, scale)
                    // scale = 2.0 表示 200% 缩放
                    // 对于 72 DPI 的基础 PDF，2.0 缩放 ≈ 144 DPI
                    // 对于 300 DPI 的扫描 PDF，2.0 缩放 ≈ 600 DPI
                    BufferedImage image = renderer.renderImage(pageNum, 2.0f);

                    // Step 2: 将 BufferedImage 转为临时文件
                    // Tesseract 需要文件路径，不能直接处理 BufferedImage
                    File tempImage = File.createTempFile("ocr_", ".png");
                    ImageIO.write(image, "png", tempImage);

                    // Step 3: OCR 识别
                    String pageText = tesseract.doOCR(tempImage);
                    pageText = pageText.trim();

                    if (!pageText.isEmpty()) {
                        pageTexts.add(pageText);
                        log.debug("第 {} 页 OCR 完成，识别 {} 个字符",
                                pageNum + 1, pageText.length());
                    }

                    // Step 4: 清理临时文件
                    if (tempImage.exists()) {
                        tempImage.delete();
                    }

                } catch (TesseractException e) {
                    log.warn("第 {} 页 OCR 失败: {}", pageNum + 1, e.getMessage());
                } catch (IOException e) {
                    log.warn("第 {} 页图片转换失败: {}", pageNum + 1, e.getMessage());
                }
            }

        } catch (IOException e) {
            log.error("PDF 加载失败: {}", e.getMessage());
            return null;
        }

        // 合并所有页的识别结果
        // 使用 [页面分隔] 标记，便于后续处理时知道页面边界
        String result = String.join("\n\n[页面分隔]\n\n", pageTexts);
        log.info("PDF OCR 完成，共识别 {} 页，成功 {} 页",
                totalPages, pageTexts.size());

        return result;
    }

    /**
     * 直接对图片文件进行 OCR
     *
     * 【支持格式】
     * PNG, JPG, JPEG, BMP, GIF, TIFF 等常见图片格式
     *
     * 【使用场景】
     * - 用户直接上传图片
     * - 文档中的嵌入图片
     * - 其他需要 OCR 的场景
     *
     * @param imagePath 图片文件路径
     * @return 识别出的文字
     */
    public String extractTextFromImage(Path imagePath) {
        initTesseract();

        if (tesseract == null) {
            log.error("Tesseract 未初始化，OCR 不可用");
            return null;
        }

        try {
            File imageFile = imagePath.toFile();
            if (!imageFile.exists()) {
                log.error("图片文件不存在: {}", imagePath);
                return null;
            }

            // Tesseract 直接对图片文件进行 OCR
            String result = tesseract.doOCR(imageFile);
            log.info("图片 OCR 完成，识别 {} 个字符: {}",
                    result.trim().length(), imagePath.getFileName());

            return result.trim();

        } catch (TesseractException e) {
            log.error("图片 OCR 失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 检查 OCR 是否可用
     *
     * 【检查内容】
     * 1. OCR 功能是否启用
     * 2. Tesseract 是否正确安装
     * 3. 语言包是否存在
     */
    public boolean isAvailable() {
        initTesseract();
        return tesseract != null;
    }

    /**
     * 获取 OCR 配置信息
     *
     * 【用途】
     * - 调试时查看配置
     * - 用户帮助文档
     */
    public String getConfigInfo() {
        StringBuilder info = new StringBuilder();
        info.append("OCR 配置信息:\n");
        info.append("- 启用状态: ").append(ocrEnabled ? "是" : "否").append("\n");
        info.append("- 语言包: ").append(ocrLanguage).append("\n");
        info.append("- 页面分割模式: ").append(psm).append("\n");
        info.append("- Tesseract 可用: ").append(isAvailable() ? "是" : "否").append("\n");
        info.append("- TESSDATA_PREFIX: ").append(
                System.getenv("TESSDATA_PREFIX") != null
                        ? System.getenv("TESSDATA_PREFIX") : "未设置")
                .append("\n");
        return info.toString();
    }
}
