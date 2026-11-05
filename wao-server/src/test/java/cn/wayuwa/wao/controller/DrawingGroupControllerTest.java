package cn.wayuwa.wao.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.mapper.DrawingGroupMapper;
import cn.wayuwa.wao.service.DrawingFileService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class DrawingGroupControllerTest {
    @Test void movingRequiresBothFileAccessAndMembersOwnGroup() {
        var files = mock(DrawingFileService.class);
        var current = mock(CurrentMember.class);
        var groups = mock(DrawingGroupMapper.class);
        var request = mock(HttpServletRequest.class);
        when(current.idOr(request, "wg")).thenReturn("m1");
        var controller = new DrawingFileController(files, current);
        ReflectionTestUtils.setField(controller, "groups", groups);
        assertThatThrownBy(() -> controller.move("dr1", new DrawingFileController.MoveRequest("other"), request)).isInstanceOf(NotFoundException.class);
        verify(groups, never()).assign(any(), any(), any());
        when(groups.owned("g1", "m1")).thenReturn(1);
        controller.move("dr1", new DrawingFileController.MoveRequest("g1"), request);
        verify(files, times(2)).get("dr1", "m1");
        verify(groups).assign("m1", "dr1", "g1");
        controller.move("dr1", new DrawingFileController.MoveRequest(null), request);
        verify(groups).unassign("m1", "dr1");
        doThrow(new NotFoundException("不可访问")).when(files).get("hidden", "m1");
        assertThatThrownBy(() -> controller.move("hidden", new DrawingFileController.MoveRequest("g1"), request)).isInstanceOf(NotFoundException.class);
        verify(groups, never()).assign("m1", "hidden", "g1");
    }
}
