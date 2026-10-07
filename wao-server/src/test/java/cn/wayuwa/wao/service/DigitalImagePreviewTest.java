package cn.wayuwa.wao.service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DigitalImagePreviewTest {
    private ReferenceImageService.Content content(BufferedImage image) throws Exception {
        var output=new ByteArrayOutputStream();ImageIO.write(image,"png",output);
        return new ReferenceImageService.Content("image/png",output.toByteArray());
    }
    @Test void largePhotoPreviewScalesWithoutChangingOriginal() throws Exception {
        var image=new BufferedImage(1536,2048,BufferedImage.TYPE_INT_RGB);
        var random=new java.util.Random(42);
        for(int y=0;y<2048;y++)for(int x=0;x<1536;x++)image.setRGB(x,y,random.nextInt());
        var original=content(image);var before=original.bytes().clone();
        var preview=DigitalImagePreview.create(original);
        assertEquals("image/jpeg",preview.contentType());
        var decoded=ImageIO.read(new ByteArrayInputStream(preview.bytes()));
        assertEquals(480,decoded.getWidth());assertEquals(640,decoded.getHeight());
        assertTrue(preview.bytes().length<300_000);assertArrayEquals(before,original.bytes());
    }
    @Test void transparentThinReferenceRemainsVisibleWithoutUpscaling() throws Exception {
        var image=new BufferedImage(1,1000,BufferedImage.TYPE_INT_ARGB);image.setRGB(0,0,0);
        var preview=DigitalImagePreview.create(content(image));
        var decoded=ImageIO.read(new ByteArrayInputStream(preview.bytes()));
        assertEquals("image/png",preview.contentType());assertEquals(1,decoded.getWidth());
        assertEquals(640,decoded.getHeight());assertTrue(decoded.getColorModel().hasAlpha());
        assertEquals(0,(decoded.getRGB(0,0)>>>24));
        var small=ImageIO.read(new ByteArrayInputStream(DigitalImagePreview.create(content(new BufferedImage(20,30,BufferedImage.TYPE_INT_RGB))).bytes()));
        assertEquals(20,small.getWidth());assertEquals(30,small.getHeight());
    }
}
