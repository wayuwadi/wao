package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class ResourceImagePreviewTest {
    @Test
    void transparentPngKeepsItsAlphaAndOriginalBytes() throws Exception {
        var source = new BufferedImage(1600, 900, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < source.getHeight(); y++) for (int x = 0; x < source.getWidth(); x++)
            source.setRGB(x, y, ((x % 256) << 24) | ((x % 256) << 16) | ((y % 256) << 8) | ((x + y) % 256));
        byte[] original = encode(source, "png");
        byte[] unchanged = original.clone();

        var result = ResourceImagePreview.create("image/png", original);
        var image = ImageIO.read(new ByteArrayInputStream(result.content()));

        assertThat(result.contentType()).isEqualTo("image/png");
        assertThat(result.content().length).isLessThan(original.length);
        assertThat(image.getWidth()).isEqualTo(768);
        assertThat(image.getColorModel().hasAlpha()).isTrue();
        assertThat(image.getRGB(0, 0) >>> 24).isLessThan(255);
        assertThat(original).containsExactly(unchanged);
    }

    @Test
    void compactLargePixelImageStillUsesThumbnailDimensions() throws Exception {
        var source = new BufferedImage(4800, 3200, BufferedImage.TYPE_INT_RGB);
        byte[] original = encode(source, "png");
        assertThat(original.length).isLessThan(220_000);

        var result = ResourceImagePreview.create("image/png", original);
        var image = ImageIO.read(new ByteArrayInputStream(result.content()));

        assertThat(image.getWidth()).isEqualTo(768);
        assertThat(image.getHeight()).isEqualTo(512);
        assertThat(result.content().length).isLessThan(original.length);
    }

    @Test
    void requiredResizeIsKeptEvenWhenHighlyCompressedOriginalHasFewerBytes() throws Exception {
        byte[] original = encode(new BufferedImage(1600, 1, BufferedImage.TYPE_INT_RGB), "png");
        var result = ResourceImagePreview.create("image/png", original);
        assertThat(ImageIO.read(new ByteArrayInputStream(result.content())).getWidth()).isEqualTo(768);
        assertThat(result.content().length).isGreaterThan(original.length);
    }

    @Test
    void evenSmallExifRotatedImagesAreReturnedInTheirCorrectOrientation() throws Exception {
        byte[] original = withOrientation(encode(new BufferedImage(20, 10, BufferedImage.TYPE_INT_RGB), "jpg"), 6);
        var result = ResourceImagePreview.create("image/jpeg", original);
        var image = ImageIO.read(new ByteArrayInputStream(result.content()));
        assertThat(image.getWidth()).isEqualTo(10);
        assertThat(image.getHeight()).isEqualTo(20);
        assertThat(DigitalImageOrientation.orientation(result.content())).isEqualTo(1);
    }

    @Test
    void phoneExifOrientationIsAppliedBeforeEncodingWithoutMetadata() throws Exception {
        var source = new BufferedImage(1600, 800, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < source.getHeight(); y++) for (int x = 0; x < source.getWidth(); x++)
            source.setRGB(x, y, ((x % 256) << 16) | ((y % 256) << 8) | ((x + y) % 256));
        byte[] original = withOrientation(encode(source, "jpg"), 6);

        var result = ResourceImagePreview.create("image/jpeg", original);
        var image = ImageIO.read(new ByteArrayInputStream(result.content()));

        assertThat(result.contentType()).isEqualTo("image/jpeg");
        assertThat(image.getWidth()).isEqualTo(384);
        assertThat(image.getHeight()).isEqualTo(768);
        assertThat(DigitalImageOrientation.orientation(result.content())).isEqualTo(1);
    }

    @Test
    void smallImagesAnimationsVectorsAndDamagedLegacyImagesPassThrough() throws Exception {
        byte[] png = encode(new BufferedImage(20, 10, BufferedImage.TYPE_INT_RGB), "png");
        assertThat(ResourceImagePreview.create("image/png", png).content()).isSameAs(png);
        for (String type : new String[]{"image/gif", "image/svg+xml", "image/webp", "image/png", "image/jpeg"}) {
            byte[] bytes = new byte[]{1, 2, 3};
            var result = ResourceImagePreview.create(type, bytes);
            assertThat(result.content()).isSameAs(bytes);
            assertThat(result.contentType()).isEqualTo(type);
        }
    }

    private static byte[] encode(BufferedImage image, String format) throws Exception {
        var output = new ByteArrayOutputStream();
        ImageIO.write(image, format, output);
        return output.toByteArray();
    }

    private static byte[] withOrientation(byte[] jpeg, int orientation) {
        byte[] app1 = new byte[36];
        app1[0] = (byte) 255; app1[1] = (byte) 225; app1[3] = 34;
        app1[4] = 'E'; app1[5] = 'x'; app1[6] = 'i'; app1[7] = 'f'; app1[10] = 'I'; app1[11] = 'I';
        var data = ByteBuffer.wrap(app1).order(ByteOrder.LITTLE_ENDIAN);
        data.putShort(12, (short) 42); data.putInt(14, 8); data.putShort(18, (short) 1);
        data.putShort(20, (short) 0x112); data.putShort(22, (short) 3); data.putInt(24, 1);
        data.putShort(28, (short) orientation);
        var result = new byte[jpeg.length + app1.length];
        System.arraycopy(jpeg, 0, result, 0, 2);
        System.arraycopy(app1, 0, result, 2, app1.length);
        System.arraycopy(jpeg, 2, result, 2 + app1.length, jpeg.length - 2);
        return result;
    }
}
