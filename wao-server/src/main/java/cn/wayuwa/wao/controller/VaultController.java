package cn.wayuwa.wao.controller;

import java.util.List;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.dto.CreateVaultGroupRequest;
import cn.wayuwa.wao.dto.CreateVaultItemRequest;
import cn.wayuwa.wao.dto.PatchVaultGroupRequest;
import cn.wayuwa.wao.dto.PatchVaultItemRequest;
import cn.wayuwa.wao.dto.ReorderVaultGroupsRequest;
import cn.wayuwa.wao.dto.VaultGroupDto;
import cn.wayuwa.wao.dto.VaultItemDto;
import cn.wayuwa.wao.dto.VaultSecretDto;
import cn.wayuwa.wao.service.VaultService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 密钥库接口。密码明文只经 {@code /api/vault/items/{id}/reveal} 按需返回一次，其余接口从不携带。
 *
 * <p>每个接口都在当前登录成员自己的分组范围内工作，归属由服务端从会话推导，不接受请求体指定。
 */
@RestController
@RequestMapping("/api/vault")
public class VaultController {

    /** 关掉登录保护的本地 / dev 环境只有一个身份，按管理员处理，与 V23 的存量归属一致。 */
    private static final String FALLBACK_OWNER = "wg";

    private final VaultService vaultService;
    private final CurrentMember currentMember;

    public VaultController(VaultService vaultService, CurrentMember currentMember) {
        this.vaultService = vaultService;
        this.currentMember = currentMember;
    }

    private String owner(HttpServletRequest http) {
        return currentMember.idOr(http, FALLBACK_OWNER);
    }

    @GetMapping
    public ApiResponse<List<VaultGroupDto>> list(HttpServletRequest http) {
        return ApiResponse.ok(vaultService.listGroups(owner(http)));
    }

    @PostMapping("/groups")
    public ApiResponse<VaultGroupDto> createGroup(@Valid @RequestBody CreateVaultGroupRequest request,
            HttpServletRequest http) {
        return ApiResponse.ok(vaultService.createGroup(request, owner(http)));
    }

    @PatchMapping("/groups/{id}")
    public ApiResponse<VaultGroupDto> patchGroup(@PathVariable String id,
            @RequestBody PatchVaultGroupRequest request, HttpServletRequest http) {
        return ApiResponse.ok(vaultService.renameGroup(id, request, owner(http)));
    }

    @DeleteMapping("/groups/{id}")
    public ApiResponse<Void> deleteGroup(@PathVariable String id, HttpServletRequest http) {
        vaultService.deleteGroup(id, owner(http));
        return ApiResponse.ok(null);
    }

    @PutMapping("/groups/order")
    public ApiResponse<Void> reorderGroups(@RequestBody ReorderVaultGroupsRequest request,
            HttpServletRequest http) {
        vaultService.reorderGroups(request.ids(), owner(http));
        return ApiResponse.ok(null);
    }

    @PostMapping("/items")
    public ApiResponse<VaultItemDto> createItem(@Valid @RequestBody CreateVaultItemRequest request,
            HttpServletRequest http) {
        return ApiResponse.ok(vaultService.createItem(request, owner(http)));
    }

    @PatchMapping("/items/{id}")
    public ApiResponse<VaultItemDto> patchItem(@PathVariable String id,
            @RequestBody PatchVaultItemRequest request, HttpServletRequest http) {
        return ApiResponse.ok(vaultService.updateItem(id, request, owner(http)));
    }

    @DeleteMapping("/items/{id}")
    public ApiResponse<Void> deleteItem(@PathVariable String id, HttpServletRequest http) {
        vaultService.deleteItem(id, owner(http));
        return ApiResponse.ok(null);
    }

    @PostMapping("/items/{id}/reveal")
    public ApiResponse<VaultSecretDto> reveal(@PathVariable String id, HttpServletRequest http) {
        return ApiResponse.ok(new VaultSecretDto(vaultService.revealSecret(id, owner(http))));
    }
}
