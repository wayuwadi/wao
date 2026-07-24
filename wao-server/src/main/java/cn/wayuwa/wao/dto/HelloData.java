package cn.wayuwa.wao.dto;

import java.time.OffsetDateTime;

public record HelloData(String message, String service, OffsetDateTime serverTime) {
}
