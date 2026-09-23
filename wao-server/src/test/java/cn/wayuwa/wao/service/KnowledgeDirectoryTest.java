package cn.wayuwa.wao.service;

import java.util.Map;
import org.junit.jupiter.api.Test;
import cn.wayuwa.wao.common.BadRequestException;
import static org.assertj.core.api.Assertions.*;

class KnowledgeDirectoryTest {
    @Test void mixedTreeSupportsFoldersInsideDocumentsAndDocumentsInsideFolders() {
        Map<String, String> tree = Map.of("f:a", "", "d:a", "f:a", "f:b", "d:a", "d:b", "f:b", "d:c", "d:b");
        KnowledgeDirectory.validate(tree);
        assertThat(KnowledgeDirectory.folderOf("d:c", tree)).isEqualTo("b");
    }
    @Test void ancestorCannotBeMovedIntoItsDescendant() {
        assertThatThrownBy(() -> KnowledgeDirectory.validate(Map.of("f:a", "d:a", "d:a", "f:a")))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("后代");
    }
    @Test void missingParentAndDocumentWithoutFolderAreRejected() {
        assertThatThrownBy(() -> KnowledgeDirectory.validate(Map.of("f:a", "f:other-kb"))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> KnowledgeDirectory.validate(Map.of("d:a", ""))).isInstanceOf(BadRequestException.class);
    }
}
