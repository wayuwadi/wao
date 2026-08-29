package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.domain.AppearancePhotoEntity;
import cn.wayuwa.wao.domain.AppearanceWorkspaceEntity;
import cn.wayuwa.wao.dto.AppearanceWorkspaceDto;
import cn.wayuwa.wao.dto.AppearanceWorkspaceDto.Garment;
import cn.wayuwa.wao.dto.AppearanceWorkspaceDto.Note;
import cn.wayuwa.wao.dto.AppearanceWorkspaceDto.Outfit;
import cn.wayuwa.wao.dto.AppearanceWorkspaceDto.Plan;
import cn.wayuwa.wao.dto.AppearanceWorkspaceDto.Profile;
import cn.wayuwa.wao.dto.AppearanceWorkspaceDto.WearLog;
import cn.wayuwa.wao.mapper.AppearancePhotoMapper;
import cn.wayuwa.wao.mapper.AppearanceWorkspaceMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.mock.web.MockMultipartFile;

class AppearanceServiceTest {
    private final AppearanceWorkspaceMapper workspaces = Mockito.mock(AppearanceWorkspaceMapper.class);
    private final AppearancePhotoMapper photos = Mockito.mock(AppearancePhotoMapper.class);
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final AppearanceService service = new AppearanceService(workspaces, photos, json);

    @Test
    void firstVisitReturnsGuidanceAndNoFakeBusinessRecords() {
        when(workspaces.selectById("wg")).thenReturn(null);

        var result = service.workspace("wg");

        assertThat(result.principles()).contains("清爽", "合适比喜欢更好");
        assertThat(result.garments()).isEmpty();
        assertThat(result.outfits()).isEmpty();
        assertThat(result.plans()).isEmpty();
        assertThat(result.wearLogs()).isEmpty();
        verify(workspaces, never()).insert(any(AppearanceWorkspaceEntity.class));
        verify(workspaces, never()).upsert(any(), any());
    }

    @Test
    void saveRecalculatesWearFactsFromLogsAndOutfitReferences() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);
        Garment shirt = garment("ag-shirt", "浅蓝衬衫");
        Garment shoes = garment("ag-shoes", "白色运动鞋");
        Outfit outfit = new Outfit("ao-work", "清爽通勤", List.of(shirt.id(), shoes.id()),
                List.of("通勤"), null, "舒服且容易保持整洁");
        var request = workspace(List.of(shirt, shoes), List.of(outfit), List.of(
                new WearLog("aw1", yesterday, outfit.id(), List.of(), "行动方便"),
                new WearLog("aw2", today, outfit.id(), List.of(shirt.id()), "没有短期不适")));
        AppearanceWorkspaceEntity saved = new AppearanceWorkspaceEntity();
        saved.setMemberId("wg");
        saved.setUpdatedAt(LocalDateTime.now());
        when(workspaces.selectById("wg")).thenReturn(saved);

        var result = service.save("wg", request);

        assertThat(result.garments()).extracting(Garment::wearCount).containsExactly(2, 2);
        assertThat(result.garments()).extracting(Garment::lastWornDate).containsOnly(today);
        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(workspaces).upsert(eq("wg"), content.capture());
        AppearanceWorkspaceDto persisted = json.readValue(content.getValue(), AppearanceWorkspaceDto.class);
        assertThat(persisted.garments()).extracting(Garment::wearCount).containsExactly(2, 2);
    }

    @Test
    void rejectsBrokenReferencesInsteadOfSavingMisleadingStatistics() {
        var request = workspace(List.of(garment("ag-shirt", "浅蓝衬衫")), List.of(
                new Outfit("ao1", "错误组合", List.of("missing"), List.of(), null, "")), List.of());

        assertThatThrownBy(() -> service.save("wg", request))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("不存在的物品");
        verify(workspaces, never()).upsert(any(), any());
    }

    @Test
    void acceptsOnlyRealSupportedPhotosAndScopesReadsByMember() {
        var upload = new MockMultipartFile("file", "look.webp", "image/webp", new byte[] {1, 2, 3});

        var result = service.uploadPhoto("wg", upload);

        assertThat(result.url()).startsWith("/api/appearance/photos/ap");
        ArgumentCaptor<AppearancePhotoEntity> photo = ArgumentCaptor.forClass(AppearancePhotoEntity.class);
        verify(photos).insert(photo.capture());
        assertThat(photo.getValue().getMemberId()).isEqualTo("wg");
        assertThat(photo.getValue().getFileContent()).containsExactly(1, 2, 3);

        when(photos.selectOwnedWithContent("ap-other", "wg")).thenReturn(null);
        assertThatThrownBy(() -> service.photo("wg", "ap-other"))
                .isInstanceOf(NotFoundException.class).hasMessageContaining("不存在");
        verify(photos).selectOwnedWithContent("ap-other", "wg");

        var fake = new MockMultipartFile("file", "look.svg", "image/svg+xml", "<svg/>".getBytes());
        assertThatThrownBy(() -> service.uploadPhoto("wg", fake))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("只支持");
    }

    private static Garment garment(String id, String name) {
        return new Garment(id, name, "上装", List.of("春"), List.of("简约"), List.of("浅蓝"),
                List.of("通勤"), "棉", "WARDROBE", null, null, null, 999, "");
    }

    private static AppearanceWorkspaceDto workspace(List<Garment> garments, List<Outfit> outfits,
            List<WearLog> logs) {
        Profile profile = new Profile(null, null, null, null, null, "", "", "", "", "", "",
                60, 80, 60, null, null, null);
        return new AppearanceWorkspaceDto(garments, outfits, profile, List.<Plan>of(), logs, List.<Note>of(),
                AppearanceService.DEFAULT_PRINCIPLES, null);
    }
}
