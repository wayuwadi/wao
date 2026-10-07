package cn.wayuwa.wao.service;

import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;

/** 私有图库展示使用轻量预览；生成使用的规范化参考资产保持原样。 */
public final class DigitalImagePreview {
    private DigitalImagePreview() { }

    /** 在拥有者鉴权之后，生成最长边 640px 的预览，保留透明通道。 */
    public static ReferenceImageService.Content create(ReferenceImageService.Content original) {
        try {
            var image=ImageIO.read(new ByteArrayInputStream(original.bytes()));
            if(image==null)throw new IllegalStateException("私有照片无法解码");
            double scale=Math.min(1d,640d/Math.max(image.getWidth(),image.getHeight()));
            boolean alpha=image.getColorModel().hasAlpha();
            var preview=new BufferedImage(Math.max(1,(int)Math.round(image.getWidth()*scale)),
                    Math.max(1,(int)Math.round(image.getHeight()*scale)),alpha?BufferedImage.TYPE_INT_ARGB:BufferedImage.TYPE_INT_RGB);
            var graphics=preview.createGraphics();
            try {graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                graphics.drawImage(image,0,0,preview.getWidth(),preview.getHeight(),null);
            }finally{graphics.dispose();}
            var output=new ByteArrayOutputStream();
            if(!ImageIO.write(preview,alpha?"png":"jpg",output))throw new IllegalStateException("私有照片预览转换失败");
            return new ReferenceImageService.Content(alpha?"image/png":"image/jpeg",output.toByteArray());
        }catch(java.io.IOException e){throw new IllegalStateException("私有照片预览转换失败",e);}
    }
}
