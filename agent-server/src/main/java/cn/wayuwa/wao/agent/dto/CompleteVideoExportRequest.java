package cn.wayuwa.wao.agent.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/** 浏览器编码并上传真实文件后，才用此请求登记导出成功。 */
public record CompleteVideoExportRequest(@Min(1) int version, @NotBlank String mediaAssetId) {
}
