package cn.wayuwa.wao.service;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** 读取有界 JPEG APP1 方向，将手机照片转成实际正向像素，之后剥离元数据。 */
final class DigitalImageOrientation {
    private DigitalImageOrientation() { }
    static BufferedImage apply(BufferedImage source,byte[] bytes) {
        int direction=orientation(bytes);if(direction==1)return source;
        int w=source.getWidth(),h=source.getHeight();boolean swap=direction>=5;
        var output=new BufferedImage(swap?h:w,swap?w:h,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<h;y++)for(int x=0;x<w;x++) {
            int tx=x,ty=y;
            switch(direction) {
                case 2 -> tx=w-1-x;
                case 3 -> {tx=w-1-x;ty=h-1-y;}
                case 4 -> ty=h-1-y;
                case 5 -> {tx=y;ty=x;}
                case 6 -> {tx=h-1-y;ty=x;}
                case 7 -> {tx=h-1-y;ty=w-1-x;}
                case 8 -> {tx=y;ty=w-1-x;}
                default -> { }
            }
            output.setRGB(tx,ty,source.getRGB(x,y));
        }
        return output;
    }
    static int orientation(byte[] b) {
        if(b.length<4||(b[0]&255)!=255||(b[1]&255)!=216)return 1;
        try {
            int p=2;
            while(p+4<=b.length) {
                if((b[p]&255)!=255)return 1;
                int marker=b[p+1]&255;if(marker==218||marker==217)return 1;
                int n=((b[p+2]&255)<<8)|(b[p+3]&255);if(n<2||p+2+n>b.length)return 1;
                if(marker==225&&n>=16&&b[p+4]=='E'&&b[p+5]=='x'&&b[p+6]=='i'&&b[p+7]=='f'&&b[p+8]==0&&b[p+9]==0) {
                    int base=p+10,end=p+2+n;
                    ByteOrder order=b[base]=='I'&&b[base+1]=='I'?ByteOrder.LITTLE_ENDIAN:b[base]=='M'&&b[base+1]=='M'?ByteOrder.BIG_ENDIAN:null;
                    if(order==null)return 1;
                    var data=ByteBuffer.wrap(b).order(order);
                    if(Short.toUnsignedInt(data.getShort(base+2))!=42)return 1;
                    long offset=Integer.toUnsignedLong(data.getInt(base+4));if(offset<8||offset>end-base-2)return 1;
                    int ifd=base+(int)offset,count=Short.toUnsignedInt(data.getShort(ifd));
                    if((long)ifd+2+(long)count*12>end)return 1;
                    for(int i=0;i<count;i++) {
                        int e=ifd+2+i*12;
                        if(Short.toUnsignedInt(data.getShort(e))==0x112&&Short.toUnsignedInt(data.getShort(e+2))==3&&data.getInt(e+4)==1) {
                            int value=Short.toUnsignedInt(data.getShort(e+8));return value>=1&&value<=8?value:1;
                        }
                    }
                }
                p+=n+2;
            }
        } catch(IndexOutOfBoundsException ignored) { }
        return 1;
    }
}
