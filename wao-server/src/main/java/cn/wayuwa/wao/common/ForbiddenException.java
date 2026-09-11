package cn.wayuwa.wao.common;

/** 已登录成员没有当前操作权限。 */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) { super(message); }
}
