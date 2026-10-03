package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 保存 AI 已完成的结果；服务端会核对原文仍是当前小记正文。 */
public record SaveNotePolishVersionRequest(
        @NotBlank @Size(max = 1_000_000) String sourceMarkdown,
        @NotBlank @Size(max = 50_000) String polishedMarkdown,
        @Size(max = 36) String aiJobId) {
    public SaveNotePolishVersionRequest(String sourceMarkdown,String polishedMarkdown) { this(sourceMarkdown,polishedMarkdown,null); }
}
