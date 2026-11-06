package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ConflictException;
import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.mapper.MemberPreferenceMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class TaskOrganizationPreferenceTest {
    private static final String KEY = "todo-named-filters-v1";
    private final MemberPreferenceMapper mapper = mock(MemberPreferenceMapper.class);
    private final CurrentMember member = mock(CurrentMember.class);
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final MemberPreferenceController controller = new MemberPreferenceController(mapper, member);

    @Test void requiresAuthenticatedIdentityAndExpectedBase() {
        assertThatThrownBy(() -> controller.get(KEY, request)).isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> controller.put(KEY, Map.of("value", "[]", "expected", ""), request)).isInstanceOf(UnauthorizedException.class);
        when(member.id(request)).thenReturn("me");
        assertThatThrownBy(() -> controller.put(KEY, Map.of("value", "[]"), request)).hasMessageContaining("expected");
        verifyNoInteractions(mapper);
    }
    @Test void acceptsLargeOrganizationDataWithoutExpandingUnrelatedKeys() {
        when(member.id(request)).thenReturn("me");
        String value="x".repeat(5000);
        when(mapper.insertIfAbsent("me", KEY, value)).thenReturn(1);
        controller.put(KEY,Map.of("value",value,"expected",""),request);
        verify(mapper).insertIfAbsent("me",KEY,value);
        verify(mapper,never()).upsert(any(),any(),any());
        assertThatThrownBy(()->controller.put("other",Map.of("value",value),request)).hasMessageContaining("4000");
        assertThatThrownBy(()->controller.put(KEY,Map.of("value","x".repeat(60001),"expected",""),request)).hasMessageContaining("60000");
    }
    @Test void refusesStaleBasesAndRacesWithoutOverwritingNewerConfiguration() {
        when(member.id(request)).thenReturn("me");
        when(mapper.find("me",KEY)).thenReturn("newer");
        assertThatThrownBy(()->controller.put(KEY,Map.of("value","draft","expected","old"),request)).isInstanceOf(ConflictException.class);
        verify(mapper,never()).compareAndSet(any(),any(),any(),any());
        when(mapper.find("me",KEY)).thenReturn("old","concurrent");
        when(mapper.compareAndSet("me",KEY,"old","draft")).thenReturn(0);
        assertThatThrownBy(()->controller.put(KEY,Map.of("value","draft","expected","old"),request)).isInstanceOf(ConflictException.class);
        verify(mapper).compareAndSet("me",KEY,"old","draft");
    }
    @Test void lostAcknowledgementRetryOfSameValueIsIdempotent() {
        when(member.id(request)).thenReturn("me");
        when(mapper.find("me",KEY)).thenReturn("saved");
        controller.put(KEY,Map.of("value","saved","expected","old"),request);
        verify(mapper,never()).insertIfAbsent(any(),any(),any());
        verify(mapper,never()).compareAndSet(any(),any(),any(),any());
    }
}
