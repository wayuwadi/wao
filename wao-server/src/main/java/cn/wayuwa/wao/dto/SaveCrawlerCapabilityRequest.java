package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 创建或更新爬虫能力；更新时 revision 用于阻止并发覆盖。 */
public record SaveCrawlerCapabilityRequest(
        @NotBlank(message = "能力名称不能为空")
        @Size(max = 128, message = "能力名称不能超过 128 个字符")
        String name,
        @Size(max = 512, message = "能力说明不能超过 512 个字符")
        String description,
        @NotBlank(message = "脚本语言不能为空")
        @Pattern(regexp = "PYTHON|JAVASCRIPT", message = "只支持 Python 或 JavaScript 源码")
        String language,
        @NotBlank(message = "源码不能为空")
        @Size(max = 262144, message = "源码不能超过 256KB")
        String sourceCode,
        boolean enabled,
        Integer revision) { }
