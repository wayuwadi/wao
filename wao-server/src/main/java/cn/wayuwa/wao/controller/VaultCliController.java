package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.common.ForbiddenException;
import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.service.VaultService;
import cn.wayuwa.wao.service.WorkspaceMcpTokenService;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Uses explicit CLI grants for metadata lookup and deposit into the member's own vault. */
@RestController
@RequestMapping("/api/vault/cli")
public class VaultCliController {
    private final WorkspaceMcpTokenService tokens;
    private final VaultService vault;

    public VaultCliController(WorkspaceMcpTokenService tokens, VaultService vault) {
        this.tokens = tokens;
        this.vault = vault;
    }

    public record Lookup(String name, String account) {}
    public record Deposit(String name, String account, String secret) {
        @Override public String toString() { return "VaultCliDeposit"; }
    }
    public record DevopsDeposit(String envelope, String expectedRevision) {
        @Override public String toString() { return "VaultCliDevopsDeposit"; }
    }

    @PostMapping("/find")
    public ResponseEntity<ApiResponse<List<VaultService.CliItem>>> find(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody Lookup request) {
        String member = member(authorization, "vault_lookup");
        return noStore(vault.findForCli(member, request.name(), request.account()));
    }

    @GetMapping("/save-access")
    public ResponseEntity<ApiResponse<Boolean>> saveAccess(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        member(authorization, "vault_save");
        return noStore(true);
    }

    @PostMapping("/save")
    public ResponseEntity<ApiResponse<VaultService.CliSave>> save(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody Deposit request) {
        String member = member(authorization, "vault_save");
        return noStore(vault.saveForCli(member, request.name(), request.account(), request.secret()));
    }

    @GetMapping("/devops/{device}")
    public ResponseEntity<ApiResponse<VaultService.DevopsEnvelope>> devops(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable String device) {
        return noStore(vault.devopsEnvelope(member(authorization, "vault_devops"), device));
    }

    @PutMapping("/devops/{device}")
    public ResponseEntity<ApiResponse<String>> saveDevops(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable String device, @RequestBody DevopsDeposit request) {
        return noStore(vault.saveDevopsEnvelope(member(authorization, "vault_devops"), device, request.envelope(), request.expectedRevision()));
    }

    private String member(String authorization, String operation) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new UnauthorizedException("工作区令牌无效或已吊销");
        }
        var token = tokens.authenticate(authorization.substring(7).trim());
        if (token == null) throw new UnauthorizedException("工作区令牌无效或已吊销");
        if (!token.permits(operation)) throw new ForbiddenException("请在能力中心为此 CLI 授予对应的密码本操作");
        return token.memberId();
    }

    private static <T> ResponseEntity<ApiResponse<T>> noStore(T data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(data));
    }
}
