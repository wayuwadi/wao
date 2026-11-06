package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;

/** 修改文件在 WAO 内展示的名称；原始文件名与下载行为保持不变。 */
public final class RenameFileRequest {
    @NotBlank(message = "文件展示名称不能为空")
    @Size(max = 255, message = "文件展示名称不能超过 255 个字符")
    private final String displayName;
    @Size(max = 255, message = "原文件展示名称不能超过 255 个字符")
    private String expectedDisplayName;
    private boolean expectedDisplayNameProvided;

    @JsonCreator
    public RenameFileRequest(@JsonProperty("displayName") String displayName) {
        this.displayName = displayName;
    }

    /** Explicit JSON null means an unnamed file; an omitted field preserves legacy callers. */
    @JsonSetter("expectedDisplayName")
    public void setExpectedDisplayName(String expectedDisplayName) {
        this.expectedDisplayName = expectedDisplayName;
        this.expectedDisplayNameProvided = true;
    }

    public String displayName() { return displayName; }
    public String expectedDisplayName() { return expectedDisplayName; }
    @JsonIgnore
    public boolean hasExpectedDisplayName() { return expectedDisplayNameProvided; }
}
