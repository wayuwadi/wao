package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateCompanionPairingRequest(
        @NotBlank @Size(max = 64) String deviceId,
        @NotBlank @Size(max = 128) String deviceName,
        @NotBlank @Size(max = 32) String platform) { }
