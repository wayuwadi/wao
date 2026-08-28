package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import cn.wayuwa.wao.domain.MemberFavoriteEntity;
import cn.wayuwa.wao.mapper.DocMapper;
import cn.wayuwa.wao.mapper.MemberFavoriteMapper;
import cn.wayuwa.wao.mapper.ProjectMapper;
import cn.wayuwa.wao.mapper.TaskMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class FavoriteServiceTest {

    private final MemberFavoriteMapper favorites = mock(MemberFavoriteMapper.class);
    private final FavoriteService service = new FavoriteService(favorites, mock(ProjectMapper.class),
            mock(TaskMapper.class), mock(DocMapper.class));

    @Test
    void savesAndResolvesWorkdayRhythmModuleFavorite() {
        when(favorites.selectCount(any())).thenReturn(0L);
        when(favorites.insert(any(MemberFavoriteEntity.class))).thenReturn(1);
        ArgumentCaptor<MemberFavoriteEntity> saved = ArgumentCaptor.forClass(MemberFavoriteEntity.class);

        service.add("wg", "module", "workday-rhythm");

        verify(favorites).insert(saved.capture());
        assertThat(saved.getValue().getMemberId()).isEqualTo("wg");
        assertThat(saved.getValue().getTargetType()).isEqualTo("module");
        assertThat(saved.getValue().getTargetId()).isEqualTo("workday-rhythm");

        MemberFavoriteEntity row = saved.getValue();
        row.setCreatedAt(LocalDateTime.of(2026, 8, 28, 10, 0));
        when(favorites.selectList(any())).thenReturn(List.of(row));
        assertThat(service.list("wg")).singleElement().satisfies(item -> {
            assertThat(item.type()).isEqualTo("module");
            assertThat(item.targetId()).isEqualTo("workday-rhythm");
            assertThat(item.label()).isEqualTo("今日节奏");
            assertThat(item.parentId()).isNull();
        });
    }
}
