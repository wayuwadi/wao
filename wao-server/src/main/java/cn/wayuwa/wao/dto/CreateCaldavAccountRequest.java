package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 添加 CalDAV 账户。
 *
 * @param name      账户显示名，可空（默认用用户名）
 * @param serverUrl CalDAV 服务器地址
 * @param username  登录用户名
 * @param password  密码或应用专用密码，仅用于服务端加密保存
 */
public record CreateCaldavAccountRequest(
        String name,
        @NotBlank(message = "请填写 CalDAV 服务器地址") String serverUrl,
        @NotBlank(message = "请填写用户名") String username,
        @NotBlank(message = "请填写密码") String password) {
}
