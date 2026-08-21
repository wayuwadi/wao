package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PollCompanionPairingRequest(@NotBlank @Size(min = 32, max = 128) String pollSecret) { }
