package cn.wayuwa.wao.controller;

import java.util.List;

import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.dto.CreateVaultGroupRequest;
import cn.wayuwa.wao.dto.CreateVaultItemRequest;
import cn.wayuwa.wao.dto.PatchVaultGroupRequest;
import cn.wayuwa.wao.dto.PatchVaultItemRequest;
import cn.wayuwa.wao.dto.VaultGroupDto;
import cn.wayuwa.wao.dto.VaultItemDto;
import cn.wayuwa.wao.dto.VaultSecretDto;
import cn.wayuwa.wao.service.VaultService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 密钥库接口。密码明文只经 {@code /api/vault/items/{id}/reveal} 按需返回一次，其余接口从不携带。 */
@RestController
@RequestMapping("/api/vault")
public class VaultController {

    private final VaultService vaultService;

    public VaultController(VaultService vaultService) {
        this.vaultService = vaultService;
    }

    @GetMapping
    public ApiResponse<List<VaultGroupDto>> list() {
        return ApiResponse.ok(vaultService.listGroups());
    }

    @PostMapping("/groups")
    public ApiResponse<VaultGroupDto> createGroup(@Valid @RequestBody CreateVaultGroupRequest request) {
        return ApiResponse.ok(vaultService.createGroup(request));
    }

    @PatchMapping("/groups/{id}")
    public ApiResponse<VaultGroupDto> patchGroup(@PathVariable String id, @RequestBody PatchVaultGroupRequest request) {
        return ApiResponse.ok(vaultService.renameGroup(id, request));
    }

    @DeleteMapping("/groups/{id}")
    public ApiResponse<Void> deleteGroup(@PathVariable String id) {
        vaultService.deleteGroup(id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/items")
    public ApiResponse<VaultItemDto> createItem(@Valid @RequestBody CreateVaultItemRequest request) {
        return ApiResponse.ok(vaultService.createItem(request));
    }

    @PatchMapping("/items/{id}")
    public ApiResponse<VaultItemDto> patchItem(@PathVariable String id, @RequestBody PatchVaultItemRequest request) {
        return ApiResponse.ok(vaultService.updateItem(id, request));
    }

    @DeleteMapping("/items/{id}")
    public ApiResponse<Void> deleteItem(@PathVariable String id) {
        vaultService.deleteItem(id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/items/{id}/reveal")
    public ApiResponse<VaultSecretDto> reveal(@PathVariable String id) {
        return ApiResponse.ok(new VaultSecretDto(vaultService.revealSecret(id)));
    }
}
