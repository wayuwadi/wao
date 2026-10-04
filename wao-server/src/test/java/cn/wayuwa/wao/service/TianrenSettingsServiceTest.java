package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import cn.wayuwa.wao.common.*;
import cn.wayuwa.wao.mapper.MemberPreferenceMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class TianrenSettingsServiceTest {
    @Test void defaultsAreEditableAndEmptyInputsDoNotOverwrite() {
        var preferences=mock(MemberPreferenceMapper.class);var service=new TianrenSettingsService(preferences,new ObjectMapper());
        var view=service.get("member");assertThat(view.version()).isZero();assertThat(view.autoImage()).isTrue();
        assertThatThrownBy(()->service.save("member",new TianrenSettingsService.Save(0,true,"","配图"))).isInstanceOf(BadRequestException.class);
        verify(preferences,never()).insertIfAbsent(any(),any(),any());
        assertThatThrownBy(()->service.get(null)).isInstanceOf(UnauthorizedException.class);
    }
    @Test void concurrentFirstSaveIsRejectedRatherThanOverwritten() {
        var preferences=mock(MemberPreferenceMapper.class);var service=new TianrenSettingsService(preferences,new ObjectMapper());
        when(preferences.insertIfAbsent(any(),any(),any())).thenReturn(0);
        assertThatThrownBy(()->service.save("m",new TianrenSettingsService.Save(0,true,"解读","配图"))).isInstanceOf(ConflictException.class);
        verify(preferences,never()).upsert(any(),any(),any());
    }
    @Test void completeSettingsCanExceedGenericPreferenceLimitWithoutPartialWrites() {
        var preferences=mock(MemberPreferenceMapper.class);var service=new TianrenSettingsService(preferences,new ObjectMapper());
        when(preferences.insertIfAbsent(any(),any(),any())).thenReturn(1);
        var view=service.save("m",new TianrenSettingsService.Save(0,false,"甲".repeat(4000),"乙".repeat(4000)));
        assertThat(view.version()).isOne();assertThat(view.imagePrompt()).hasSize(4000);assertThat(view.autoImage()).isFalse();
        when(preferences.find("m",TianrenSettingsService.KEY)).thenReturn("{broken");
        assertThatThrownBy(()->service.get("m")).isInstanceOf(BadRequestException.class);
    }
}
