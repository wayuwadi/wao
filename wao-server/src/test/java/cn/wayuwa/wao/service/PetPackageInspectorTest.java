package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.compress.archivers.zip.*;
import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class PetPackageInspectorTest {
    final PetPackageInspector inspector = new PetPackageInspector(new ObjectMapper());
    static String manifest = "{\"id\":\"test-pet\",\"displayName\":\"测试宠物\",\"description\":\"原创图集\",\"spriteVersionNumber\":2,\"spritesheetPath\":\"spritesheet.png\"}";
    static byte[] png(int height) throws IOException { var out=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(1536,height,BufferedImage.TYPE_INT_ARGB),"png",out);return out.toByteArray(); }
    static byte[] zip(String[] names, byte[][] contents, boolean link) throws IOException {
        var out=new ByteArrayOutputStream();try(var zip=new ZipArchiveOutputStream(out)){for(int i=0;i<names.length;i++){var entry=new ZipArchiveEntry(names[i]);if(link)entry.setUnixMode(0120777);zip.putArchiveEntry(entry);zip.write(contents[i]);zip.closeArchiveEntry();}}return out.toByteArray();
    }
    @Test void buildsRealPngPackAndCanonicalZipRoundTrips() throws Exception {
        var pack=inspector.create(manifest,png(2288));
        assertThat(pack.slug()).isEqualTo("test-pet");assertThat(pack.sha256()).hasSize(64);
        assertThat(inspector.inspect(pack.archive()).archive()).isEqualTo(pack.archive());
        var wrapped=zip(new String[]{"pet/pet.json","pet/spritesheet.png"},new byte[][]{manifest.getBytes(java.nio.charset.StandardCharsets.UTF_8),png(2288)},false);
        assertThat(inspector.inspect(wrapped).archive()).isEqualTo(pack.archive());
    }
    @Test void rejectsTraversalLinksDuplicatesScriptsAndOversizedInflation() throws Exception {
        for(String path:List.of("../pet.json","/pet.json","C:/pet.json","pet/../pet.json")) {
            var bytes=zip(new String[]{path},new byte[][]{"{}".getBytes()},false);
            assertThatThrownBy(()->inspector.inspect(bytes)).isInstanceOf(BadRequestException.class);
        }
        var symlink=zip(new String[]{"pet.json"},new byte[][]{"{}".getBytes()},true);
        assertThatThrownBy(()->inspector.inspect(symlink)).hasMessageContaining("符号链接");
        var duplicate=zip(new String[]{"pet.json","pet.json"},new byte[][]{"{}".getBytes(),"{}".getBytes()},false);
        assertThatThrownBy(()->inspector.inspect(duplicate)).hasMessageContaining("重复");
        var script=zip(new String[]{"pet.json","install.sh"},new byte[][]{"{}".getBytes(),"echo bad".getBytes()},false);
        assertThatThrownBy(()->inspector.inspect(script)).hasMessageContaining("不支持");
        var bomb=zip(new String[]{"pet.json"},new byte[][]{new byte[12*1024*1024+1]},false);
        assertThatThrownBy(()->inspector.inspect(bomb)).hasMessageContaining("12 MiB");
    }
    @Test void checksVersionDimensionsManifestAndImageData() throws Exception {
        var sprite=png(2288);
        assertThatThrownBy(()->inspector.create(manifest.replace("test-pet","../pet"),sprite)).hasMessageContaining("标识");
        assertThatThrownBy(()->inspector.create(manifest.replace("Number\":2","Number\":3"),sprite)).hasMessageContaining("v1/v2");
        assertThatThrownBy(()->inspector.create(manifest.replace("Number\":2","Number\":1"),sprite)).hasMessageContaining("1872");
        assertThatThrownBy(()->inspector.create(manifest,new byte[]{1,2,3})).hasMessageContaining("PNG");
        assertThatThrownBy(()->inspector.create(manifest.replace("spritesheet.png","../../bad"),sprite)).isInstanceOf(BadRequestException.class);
    }
    @Test void validatesExistingZhangSanfengWebpWithoutPublishingIt() throws Exception {
        Path assets=Path.of("../wao-web/src/assets/pets/zhang-sanfeng");
        var pack=inspector.create(Files.readString(assets.resolve("pet.json")),Files.readAllBytes(assets.resolve("spritesheet.webp")));
        assertThat(pack.version()).isEqualTo(2);assertThat(pack.type()).isEqualTo("image/webp");
        var broken=Arrays.copyOf(pack.sprite(),40);
        assertThatThrownBy(()->inspector.create(Files.readString(assets.resolve("pet.json")),broken)).isInstanceOf(BadRequestException.class);
    }
}
