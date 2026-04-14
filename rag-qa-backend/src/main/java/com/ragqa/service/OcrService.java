package com.ragqa.service;

import lombok.extern.slf4j.Slf4j;
import net.java.dev.tess4j.Tesseract;
import net.java.dev.tess4j.TesseractException;
import net.java.dev.tess4j.util.ImageIOHelper;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * OCR 光学字符识别服务
 *
 * 作用：从图片型 PDF 或扫描版 PDF 中提取文字内容
 *
 * 为什么需要 OCR：
 * ┌─────────────────────────────────────────────────────────────────┐
 * │                    PDF 文档类型                                   │
 * ├─────────────────────────────────────────────────────────────────┤
 * │                                                                 │
 * │   📄 可搜索型 PDF（Searchable PDF）                              │
 * │   ├── 文字层：可以直接选中、复制文字                              │
 * │   ├── 图片层：可能也包含扫描的图片                                │
 * │   └── 处理方式：直接用 Tika 提取文字层即可                       │
 * │                                                                 │
 * │   📷 扫描版 PDF（Scanned PDF）                                   │
 * │   ├── 只有图片层：每一页是一张扫描图片                            │
 * │   ├── 没有文字层：无法选中、复制文字                              │
 * │   └── 处理方式：必须先 OCR 识别文字                              │
 * │                                                                 │
 * │   🖼️ 图片型 PDF（Image PDF）                                    │
 * │   ├── 整页是一张图片                                             │
 * │   ├── 常见于：从图片直接转换的 PDF                               │
 * │   └── 处理方式：必须 OCR                                          │
 * │                                                                 │
 * └─────────────────────────────────────────────────────────────────┘
 *
 * OCR 工作流程：
 *
 *     ┌─────────────────┐
 *     │   加载 PDF 文件   │
 *     └────────┬────────┘
 *              ▼
 *     ┌─────────────────┐
 *     │ 检测是否有文字层  │ ───有文字层───▶ 直接提取，跳过 OCR
 *     └────────┬────────┘
 *              │无文字层/文字层为空
 *              ▼
 *     ┌─────────────────┐
 *     │ 将 PDF 转为图片   │  (PDFRenderer)
 *     └────────┬────────┘
 *              ▼
 *     ┌─────────────────┐
 *     │  Tesseract OCR   │  每页独立识别
 *     │  识别文字         │
 *     └────────┬────────┘
 *              ▼
 *     ┌─────────────────┐
 *     │ 合并所有页结果    │
 *     └────────┬────────┘
 *              ▼
 *     ┌─────────────────┐
 *     │   返回完整文本    │
 *     └─────────────────┘
 *
 * Tesseract 语言包：
 * - eng: 英文（默认）
 * - chi_sim: 简体中文
 * - chi_tra: 繁体中文
 * - jpn: 日语
 * - kor: 韩语
 *
 * 安装说明（macOS）：
 * ```bash
 * brew install tesseract
 * brew install tesseract-lang  # 包含所有语言包
 *
 * # 或单独安装需要的语言
 * brew install tesseract --with-chi-sim  # 中文简体
 * ```
 *
 * 配置环境变量：
 * ```bash
 * export TESSDATA_PREFIX=/usr/local/share/tessdata
 * ```
 *
 * 配置项：
 * - ocr.enabled: 是否启用 OCR（默认 true）
 * - ocr.language: OCR 语言包（默认 chi_sim+eng）
 * - ocr.psm: 页面分割模式（默认 3 = 自动分页）
 */
@Service
@Slf4j
public class OcrService {

    /**
     * Tesseract 实例
     *
     * 注意：Tesseract 不是线程安全的，每个线程应使用独立实例
     * 但这里我们使用单例 + synchronized 来保证线程安全
     */
    private Tesseract tesseract;

    /** 是否启用 OCR */
    @Value("${ocr.enabled:true}")
    private boolean ocrEnabled;

    /** OCR 语言包 */
    @Value("${ocr.language:chi_sim+eng}")
    private String ocrLanguage;

    /** 页面分割模式 (PSM) */
    @Value("${ocr.psm:3}")
    private int psm;

    /**
     * 初始化 Tesseract
     *
     * Tesseract 需要：
     * 1. TESSDATA_PREFIX 环境变量指向语言包目录
     * 2. 指定要使用的语言包名称
     */
    private synchronized void initTesseract() {
        if (tesseract != null) {
            return;
        }

        try {
            tesseract = new Tesseract();

            // 设置语言包路径
            // TESSDATA_PREFIX 环境变量通常设为 /usr/local/share/tessdata
            // Tesseract 会自动在这个目录下查找对应语言的文件
            // 例如：chi_sim.traineddata -> /usr/local/share/tessdata/chi_sim.traineddata
            String tessDataPath = System.getenv("TESSDATA_PREFIX");
            if (tessDataPath != null && !tessDataPath.isEmpty()) {
                tesseract.setDatapath(tessDataPath);
                log.info("OCR 使用语言包路径: {}", tessDataPath);
            } else {
                // 尝试常见路径
                String[] commonPaths = {
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
            // 3 = 全自动分页 (PSM_AUT) ← 常用
            // 4 = 假设一列可变大小的文本
            // 6 = 假设一个统一块文本
            // 11 = 稀疏文本，按任意顺序
            // 12 = OCR 作为单行
            tesseract.setPageSegMode(String.valueOf(psm));

            // 设置白名单（可选，提高准确率）
            // tesseract.setWhitelist("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz.,!?'-");

        } catch (Exception e) {
            log.error("OCR 初始化失败: {}", e.getMessage());
            tesseract = null;
        }
    }

    /**
     * 从 PDF 中提取文字（智能模式）
     *
     * 自动判断 PDF 类型，选择最优提取方式：
     * 1. 先尝试直接提取文字层（高效）
     * 2. 如果文字层为空，启用 OCR（精准但慢）
     *
     * @param filePath PDF 文件路径
     * @return 提取的文字内容
     */
    public String extractTextFromPdf(Path filePath) {
        if (!ocrEnabled) {
            log.info("OCR 已禁用，直接使用 Tika 提取");
            return null;
        }

        try {
            // Step 1: 尝试直接提取文字层
            String directText = extractTextLayer(filePath);
            log.info("PDF 文字层提取结果长度: {}", directText.length());

            // Step 2: 判断是否需要 OCR
            // 如果提取的文字过少（可能是扫描版），启用 OCR
            if (directText.trim().length() < 50) {
                log.info("PDF 文字层内容过少（{} 字符），启用 OCR", directText.trim().length());
                String ocrText = extractTextByOcr(filePath);

                // 合并结果：优先使用文字层，加上 OCR 结果
                // 这样可以覆盖文字层+图片层的混合 PDF
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
     * 使用 PDFBox 的 PDFTextStripper 提取
     * 这种方式快速高效，适合有文字层的 PDF
     *
     * @param filePath PDF 文件路径
     * @return 提取的文字内容
     */
    private String extractTextLayer(Path filePath) throws IOException {
        try (PDDocument document = Loader.loadPDF(filePath.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document);
        }
    }

    /**
     * 使用 OCR 从 PDF 中提取文字
     *
     * 工作流程：
     * 1. 将 PDF 每页渲染为图片
     * 2. 对每张图片运行 Tesseract OCR
     * 3. 合并所有页的识别结果
     *
     * @param filePath PDF 文件路径
     * @return OCR 识别的文字内容
     */
    private String extractTextByOcr(Path filePath) {
        // 初始化 Tesseract
        initTesseract();

        if (tesseract == null) {
            log.error("Tesseract 未初始化，OCR 不可用");
            return null;
        }

        List<String> pageTexts = new ArrayList<>();
        int totalPages = 0;

        try (PDDocument document = Loader.loadPDF(filePath.toFile())) {
            totalPages = document.getNumberOfPages();
            log.info("PDF 共 {} 页，开始 OCR 识别", totalPages);

            PDFRenderer renderer = new PDFRenderer(document);

            for (int pageNum = 0; pageNum < totalPages; pageNum++) {
                try {
                    // 渲染当前页为图片（DPI 越高越清晰，但越慢）
                    // 300 DPI 是一个平衡点，兼顾速度和准确率
                    BufferedImage image = renderer.renderImage(pageNum, 2.0f); // 2.0 = 200% 缩放 ≈ 144 DPI

                    // 将 BufferedImage 转为临时文件（Tesseract 需要文件路径）
                    File tempImage = ImageIOHelper.createTiffFromBufferedImage(image);

                    // OCR 识别
                    String pageText = tesseract.doOCR(tempImage);
                    pageText = pageText.trim();

                    if (!pageText.isEmpty()) {
                        pageTexts.add(pageText);
                        log.debug("第 {} 页 OCR 完成，识别 {} 个字符", pageNum + 1, pageText.length());
                    }

                    // 清理临时文件
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
        // 用换行符分隔，便于后续分块时保持页面边界
        String result = String.join("\n\n[页面分隔]\n\n", pageTexts);
        log.info("PDF OCR 完成，共识别 {} 页，成功 {} 页",
                totalPages, pageTexts.size());

        return result;
    }

    /**
     * 直接对图片文件进行 OCR
     *
     * 支持：PNG, JPG, JPEG, BMP, GIF, TIFF 等常见图片格式
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
            log.info("图片 OCR 完成，识别 {} 个字符: {}", result.trim().length(), imagePath.getFileName());

            return result.trim();

        } catch (TesseractException e) {
            log.error("图片 OCR 失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 检查 OCR 是否可用
     *
     * @return true 如果 Tesseract 已正确安装和配置
     */
    public boolean isAvailable() {
        initTesseract();
        return tesseract != null;
    }

    /**
     * 获取 OCR 配置信息
     *
     * @return 配置信息描述
     */
    public String getConfigInfo() {
        StringBuilder info = new StringBuilder();
        info.append("OCR 配置信息:\n");
        info.append("- 启用状态: ").append(ocrEnabled ? "是" : "否").append("\n");
        info.append("- 语言包: ").append(ocrLanguage).append("\n");
        info.append("- 页面分割模式: ").append(psm).append("\n");
        info.append("- Tesseract 可用: ").append(isAvailable() ? "是" : "否").append("\n");
        info.append("- TESSDATA_PREFIX: ").append(System.getenv("TESSDATA_PREFIX") != null
                ? System.getenv("TESSDATA_PREFIX") : "未设置").append("\n");
        return info.toString();
    }
}
