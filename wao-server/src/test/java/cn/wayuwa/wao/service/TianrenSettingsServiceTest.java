package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import cn.wayuwa.wao.common.*;
import cn.wayuwa.wao.mapper.MemberPreferenceMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class TianrenSettingsServiceTest {
    @Test void historicalSettingsAndSnapshotsUseIndependentDefaults() throws Exception {
        var preferences=mock(MemberPreferenceMapper.class);var models=mock(LocalCodexModelService.class);var json=new ObjectMapper();
        when(preferences.find("m",TianrenSettingsService.KEY)).thenReturn("{\"version\":2,\"autoImage\":true,\"interpretationPrompt\":\"旧解读\",\"imagePrompt\":\"旧配图\"}");
        var view=new TianrenSettingsService(preferences,json,models).get("m");
        assertThat(view.model()).isEqualTo("gpt-6-astra");assertThat(view.reasoningEffort()).isEqualTo("medium");
        var snapshot=json.readValue("{\"version\":2,\"autoImage\":true,\"interpretationPrompt\":\"旧解读\",\"imagePrompt\":\"旧配图\"}",TianrenSettingsService.View.class);
        assertThat(snapshot.reasoningEffort()).isEqualTo("medium");verifyNoInteractions(models);
    }
    @Test void invalidSelectionCannotWriteAndLegacyPutPreservesCurrentSelection() {
        var preferences=mock(MemberPreferenceMapper.class);var models=mock(LocalCodexModelService.class);
        when(preferences.find("m",TianrenSettingsService.KEY)).thenReturn("{\"version\":1,\"autoImage\":true,\"interpretationPrompt\":\"解读\",\"imagePrompt\":\"配图\",\"model\":\"gpt-6.1-sol\",\"reasoningEffort\":\"high\"}");
        when(models.validate("gpt-6.1-sol")).thenReturn("gpt-6.1-sol");
        when(models.validateEffort("gpt-6.1-sol","max")).thenThrow(new BadRequestException("不支持此深度"));
        when(models.validateEffort("gpt-6.1-sol","high")).thenReturn("high");
        var service=new TianrenSettingsService(preferences,new ObjectMapper(),models);
        assertThatThrownBy(()->service.save("m",new TianrenSettingsService.Save(1,true,"解读","配图","gpt-6.1-sol","max"))).isInstanceOf(BadRequestException.class);
        verify(preferences,never()).compareAndSet(any(),any(),any(),any());
        when(preferences.compareAndSet(any(),any(),any(),any())).thenReturn(1);
        var view=service.save("m",new TianrenSettingsService.Save(1,true,"新解读","新配图"));
        assertThat(view.model()).isEqualTo("gpt-6.1-sol");assertThat(view.reasoningEffort()).isEqualTo("high");
    }
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
