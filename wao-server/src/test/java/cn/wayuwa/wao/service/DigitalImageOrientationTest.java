package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.*;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

class DigitalImageOrientationTest {
    private byte[] jpeg(int value) {
        byte[] b=new byte[40];b[0]=(byte)255;b[1]=(byte)216;b[2]=(byte)255;b[3]=(byte)225;b[5]=34;
        b[6]='E';b[7]='x';b[8]='i';b[9]='f';b[12]='I';b[13]='I';
        var d=ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);d.putShort(14,(short)42);d.putInt(16,8);d.putShort(20,(short)1);
        d.putShort(22,(short)0x112);d.putShort(24,(short)3);d.putInt(26,1);d.putShort(30,(short)value);return b;
    }
    @Test void rotatesPhonePortraitIntoPixelsAndMirrorsCorrectly() {
        var image=new BufferedImage(2,3,BufferedImage.TYPE_INT_RGB);image.setRGB(0,0,0xffff0000);image.setRGB(1,2,0xff00ff00);
        var rotated=DigitalImageOrientation.apply(image,jpeg(6));
        assertThat(rotated.getWidth()).isEqualTo(3);assertThat(rotated.getHeight()).isEqualTo(2);
        assertThat(rotated.getRGB(2,0)).isEqualTo(image.getRGB(0,0));assertThat(rotated.getRGB(0,1)).isEqualTo(image.getRGB(1,2));
        var mirrored=DigitalImageOrientation.apply(image,jpeg(2));assertThat(mirrored.getRGB(1,0)).isEqualTo(image.getRGB(0,0));
    }
    @Test void malformedOffsetsAndUnknownDirectionsCannotReadPastApp1() {
        assertThat(DigitalImageOrientation.orientation(jpeg(9))).isEqualTo(1);
        byte[] invalid=jpeg(6);ByteBuffer.wrap(invalid).order(ByteOrder.LITTLE_ENDIAN).putInt(16,Integer.MAX_VALUE);
        assertThat(DigitalImageOrientation.orientation(invalid)).isEqualTo(1);
        assertThat(DigitalImageOrientation.orientation(new byte[]{1,2})).isEqualTo(1);
    }
}
