package cn.wayuwa.wao.service;

import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Set;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/** 有界解码资源库缩略图；只生成派生内容，不改写原图。 */
final class ResourceImagePreview {
    private static final int MAX_EDGE = 768;
    private static final long MAX_SOURCE_PIXELS = 100_000_000L;
    private static final Set<String> RASTER_TYPES = Set.of("image/png", "image/jpeg", "image/bmp");

    private ResourceImagePreview() { }

    static ImageService.Preview create(String type, byte[] original) {
        // GIF/WebP/SVG keep animation/vector behavior and do not invoke unsupported decoders.
        if (!RASTER_TYPES.contains(type == null ? "" : type)) return new ImageService.Preview(type, original);
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(original))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) return new ImageService.Preview(type, original);
            var reader = readers.next();
            BufferedImage decoded;
            boolean needsDimensionsOrOrientation;
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > 32_768 || height > 32_768
                        || (long) width * height > MAX_SOURCE_PIXELS) return new ImageService.Preview(type, original);
                int orientation = DigitalImageOrientation.orientation(original);
                needsDimensionsOrOrientation = Math.max(width, height) > MAX_EDGE || orientation != 1;
                if (Math.max(width, height) <= MAX_EDGE && original.length <= 220_000 && orientation == 1)
                    return new ImageService.Preview(type, original);
                var parameters = reader.getDefaultReadParam();
                // Decode at most approximately 1536 pixels per edge before final quality scaling.
                int sample = Math.max(1, (int) Math.ceil(Math.max(width, height) / (MAX_EDGE * 2.0)));
                parameters.setSourceSubsampling(sample, sample, 0, 0);
                decoded = reader.read(0, parameters);
            } finally {
                reader.dispose();
            }
            boolean alpha = decoded.getColorModel().hasAlpha();
            decoded = DigitalImageOrientation.apply(decoded, original);
            double scale = Math.min(1.0, (double) MAX_EDGE / Math.max(decoded.getWidth(), decoded.getHeight()));
            int width = Math.max(1, (int) Math.round(decoded.getWidth() * scale));
            int height = Math.max(1, (int) Math.round(decoded.getHeight() * scale));
            var thumbnail = new BufferedImage(width, height, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
            var graphics = thumbnail.createGraphics();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                graphics.drawImage(decoded, 0, 0, width, height, null);
            } finally {
                graphics.dispose();
            }
            var output = new ByteArrayOutputStream();
            String previewType = alpha ? "image/png" : "image/jpeg";
            var writers = ImageIO.getImageWritersByMIMEType(previewType);
            if (!writers.hasNext()) return new ImageService.Preview(type, original);
            var writer = writers.next();
            try (var imageOutput = new MemoryCacheImageOutputStream(output)) {
                writer.setOutput(imageOutput);
                var options = writer.getDefaultWriteParam();
                if (!alpha) {
                    options.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                    options.setCompressionQuality(0.84f);
                }
                writer.write(null, new IIOImage(thumbnail, null, null), options);
            } finally {
                writer.dispose();
            }
            byte[] bytes = output.toByteArray();
            return needsDimensionsOrOrientation || bytes.length < original.length ? new ImageService.Preview(previewType, bytes)
                    : new ImageService.Preview(type, original);
        } catch (IOException | RuntimeException exception) {
            // Unknown/damaged historical content remains readable through its original bytes.
            return new ImageService.Preview(type, original);
        }
    }
}
