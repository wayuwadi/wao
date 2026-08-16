package cn.wayuwa.wao.service;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.common.VaultCryptoService;
import cn.wayuwa.wao.config.NeteaseMusicProperties;
import cn.wayuwa.wao.domain.NeteaseMusicBindingEntity;
import cn.wayuwa.wao.domain.NeteaseMusicOauthStateEntity;
import cn.wayuwa.wao.dto.NeteaseMusicAuthorizeDto;
import cn.wayuwa.wao.dto.NeteaseMusicPlayUrlDto;
import cn.wayuwa.wao.dto.NeteaseMusicStatusDto;
import cn.wayuwa.wao.dto.NeteaseMusicTrackDto;
import cn.wayuwa.wao.dto.NeteaseMusicTrackPageDto;
import cn.wayuwa.wao.dto.NeteasePlaybackRequest;
import cn.wayuwa.wao.mapper.NeteaseMusicBindingMapper;
import cn.wayuwa.wao.mapper.NeteaseMusicOauthStateMapper;

/** 成员级网易云音乐 OAuth、红心歌单、播放地址与官方播放回传。 */
@Service
public class NeteaseMusicService {
    private static final String CONNECTED = "CONNECTED";
    private static final String REAUTH_REQUIRED = "REAUTH_REQUIRED";
    private static final String TOKEN_PATH = "/openapi/music/basic/user/oauth2/token/get/v2";
    private static final String REFRESH_PATH = "/openapi/music/basic/user/oauth2/token/refresh/v2";
    private static final String PROFILE_PATH = "/openapi/music/basic/user/profile/get/v2";
    private static final String STAR_PLAYLIST_PATH = "/openapi/music/basic/playlist/star/get/v2";
    private static final String PLAYLIST_SONGS_PATH = "/openapi/music/basic/playlist/song/list/get/v3";
    private static final String PLAY_URL_PATH = "/openapi/music/basic/song/playurl/get/v2";
    private static final String PLAYBACK_PATH = "/openapi/music/basic/play/data/record";

    private final NeteaseMusicBindingMapper bindingMapper;
    private final NeteaseMusicOauthStateMapper stateMapper;
    private final NeteaseMusicClient client;
    private final VaultCryptoService crypto;

    public NeteaseMusicService(NeteaseMusicBindingMapper bindingMapper, NeteaseMusicOauthStateMapper stateMapper,
            NeteaseMusicClient client, VaultCryptoService crypto) {
        this.bindingMapper = bindingMapper;
        this.stateMapper = stateMapper;
        this.client = client;
        this.crypto = crypto;
    }

    /** 当前成员的绑定状态；服务端未配置时返回可理解状态而不是伪装成未绑定。 */
    public NeteaseMusicStatusDto status(String memberId) {
        if (!client.configured()) return new NeteaseMusicStatusDto(false, false, false, null, null, null,
                "管理员尚未完成网易云音乐开放平台配置");
        NeteaseMusicBindingEntity binding = bindingMapper.selectById(memberId);
        if (binding == null) return new NeteaseMusicStatusDto(true, false, false, null, null, null,
                "绑定自己的网易云音乐账号后，可播放红心歌单");
        boolean connected = CONNECTED.equals(binding.getStatus());
        boolean reauth = !connected;
        return new NeteaseMusicStatusDto(true, connected, reauth, binding.getNickname(), binding.getAvatarUrl(),
                binding.getTokenExpiresAt(), reauth ? safeError(binding.getLastError()) : "已连接成员自己的网易云音乐账号");
    }

    /** 创建十分钟有效的 OAuth state，并返回官方 H5 授权页。 */
    @Transactional
    public NeteaseMusicAuthorizeDto authorize(String memberId, String netStatus) {
        if (!StringUtils.hasText(memberId)) throw new BadRequestException("无法识别当前成员");
        if (!client.configured()) throw new BadRequestException("管理员尚未完成网易云音乐开放平台配置");
        LocalDateTime now = LocalDateTime.now();
        stateMapper.delete(new LambdaQueryWrapper<NeteaseMusicOauthStateEntity>()
                .eq(NeteaseMusicOauthStateEntity::getMemberId, memberId)
                .or().lt(NeteaseMusicOauthStateEntity::getExpiresAt, now));
        String state = compactId().substring(0, 30);
        NeteaseMusicBindingEntity existingBinding = bindingMapper.selectById(memberId);
        NeteaseMusicOauthStateEntity entity = new NeteaseMusicOauthStateEntity();
        entity.setState(state);
        entity.setMemberId(memberId);
        entity.setDeviceId(existingBinding != null && StringUtils.hasText(existingBinding.getDeviceId())
                ? existingBinding.getDeviceId() : "wao" + compactId());
        entity.setNetStatus(netStatus);
        entity.setExpiresAt(now.plusMinutes(10));
        entity.setCreatedAt(now);
        stateMapper.insert(entity);

        NeteaseMusicProperties properties = client.properties();
        String url = UriComponentsBuilder.fromUriString(properties.authorizeUrl())
                .queryParam("clientId", properties.appId())
                .queryParam("state", state)
                .queryParam("clientType", properties.clientType())
                .queryParam("redirectUrl", properties.redirectUri())
                .build().encode().toUriString();
        return new NeteaseMusicAuthorizeDto(url);
    }

    /** 校验回调 state、换取成员令牌、读取真实昵称并加密落库。 */
    @Transactional
    public URI callback(String code, String state, String clientIp) {
        if (!StringUtils.hasText(code) || code.length() > 512) {
            throw new BadRequestException("网易云授权未完成，请重新发起绑定");
        }
        if (!StringUtils.hasText(state) || !state.matches("[A-Za-z0-9]{20,30}")) {
            throw new BadRequestException("网易云授权状态无效，请重新发起绑定");
        }
        NeteaseMusicOauthStateEntity oauth = stateMapper.selectById(state);
        if (oauth == null || oauth.getExpiresAt().isBefore(LocalDateTime.now())) {
            if (oauth != null) stateMapper.deleteById(state);
            throw new BadRequestException("网易云授权已过期，请重新发起绑定");
        }
        stateMapper.deleteById(state);
        JsonNode token = client.call(TOKEN_PATH, null, oauth.getDeviceId(), clientIp, oauth.getNetStatus(),
                Map.of("grantCode", code)).path("data");
        String accessToken = required(token, "accessToken", "网易云未返回 accessToken");
        String refreshToken = required(token, "refreshToken", "网易云未返回 refreshToken");
        long expires = Math.max(60, token.path("expireIn").asLong(604800));
        JsonNode profile = client.call(PROFILE_PATH, accessToken, oauth.getDeviceId(), clientIp,
                oauth.getNetStatus(), Map.of()).path("data");
        if (profile.isMissingNode() || profile.isNull()) throw new BadRequestException("网易云账号信息读取失败，请重新授权");

        LocalDateTime now = LocalDateTime.now();
        NeteaseMusicBindingEntity binding = bindingMapper.selectById(oauth.getMemberId());
        boolean inserting = binding == null;
        if (inserting) {
            binding = new NeteaseMusicBindingEntity();
            binding.setMemberId(oauth.getMemberId());
            binding.setCreatedAt(now);
        }
        encryptTokens(binding, accessToken, refreshToken);
        binding.setTokenExpiresAt(now.plusSeconds(expires));
        binding.setNeteaseUserId(profile.path("id").asText(null));
        binding.setNickname(profile.path("nickname").asText("网易云音乐用户"));
        binding.setAvatarUrl(profile.path("avatarUrl").asText(null));
        binding.setDeviceId(oauth.getDeviceId());
        binding.setStatus(CONNECTED);
        binding.setLastError(null);
        binding.setUpdatedAt(now);
        if (inserting) bindingMapper.insert(binding); else bindingMapper.updateById(binding);
        return frontendResult("connected");
    }

    /** OAuth 失败时只返回固定状态，不把第三方错误和敏感参数拼进 URL。 */
    public URI frontendResult(String result) {
        return UriComponentsBuilder.fromUriString(client.properties().frontendUrl())
                .queryParam("netease", result).build().encode().toUri();
    }

    /** 删除当前成员自己的本地绑定；其他成员不受影响。 */
    @Transactional
    public void unbind(String memberId) {
        bindingMapper.deleteById(memberId);
        stateMapper.delete(new LambdaQueryWrapper<NeteaseMusicOauthStateEntity>()
                .eq(NeteaseMusicOauthStateEntity::getMemberId, memberId));
    }

    /** 读取当前成员红心歌单的一页歌曲。 */
    public NeteaseMusicTrackPageDto likedSongs(String memberId, int requestedOffset, int requestedLimit,
            String clientIp, String netStatus) {
        int offset = Math.max(0, requestedOffset);
        int limit = Math.max(10, Math.min(100, requestedLimit));
        NeteaseMusicBindingEntity binding = connectedBinding(memberId);
        JsonNode playlist = callAuthorized(binding, STAR_PLAYLIST_PATH, Map.of(), clientIp, netStatus).path("data");
        if (playlist.isMissingNode() || playlist.isNull()) throw new NotFoundException("网易云红心歌单不存在");
        String playlistId = required(playlist, "id", "网易云红心歌单缺少标识");
        Map<String, Object> request = Map.of("playlistId", playlistId, "limit", limit, "offset", offset,
                "qualityFlag", true);
        JsonNode root = callAuthorized(binding, PLAYLIST_SONGS_PATH, request, clientIp, netStatus);
        List<NeteaseMusicTrackDto> tracks = new ArrayList<>();
        JsonNode data = root.path("data");
        if (data.isArray()) for (JsonNode song : data) tracks.add(toTrack(song));
        return new NeteaseMusicTrackPageDto(tracks, playlist.path("trackCount").asInt(tracks.size()), offset,
                limit, playlistId, playlist.path("name").asText("我喜欢的音乐"));
    }

    /** 获取短时播放地址；只返回 HTTPS，避免 WAO 页面产生混合内容。 */
    public NeteaseMusicPlayUrlDto playUrl(String memberId, String songId, String clientIp, String netStatus) {
        if (!StringUtils.hasText(songId)) throw new BadRequestException("歌曲标识不能为空");
        NeteaseMusicBindingEntity binding = connectedBinding(memberId);
        JsonNode root = callAuthorized(binding, PLAY_URL_PATH, Map.of("songId", songId, "bitrate", 320),
                clientIp, netStatus);
        JsonNode data = root.path("data");
        String url = data.path("url").asText(null);
        if (!"200".equals(root.path("subCode").asText()) || !StringUtils.hasText(url)) {
            throw new BadRequestException(root.path("message").asText("这首歌当前无法在此设备播放"));
        }
        if (url.startsWith("http://")) url = "https://" + url.substring(7);
        if (!url.startsWith("https://")) throw new BadRequestException("网易云返回了不安全的播放地址");
        int rawBitrate = data.path("br").asInt(320);
        int bitrate = rawBitrate > 10_000 ? Math.max(1, rawBitrate / 1000) : rawBitrate;
        JsonNode trial = data.path("freeTrail");
        return new NeteaseMusicPlayUrlDto(url, bitrate, trial.path("start").isNumber() ? trial.path("start").asInt() : null,
                trial.path("end").isNumber() ? trial.path("end").asInt() : null);
    }

    /** 按官方协议回传一首歌唯一的一次 startplay 或 play 记录。 */
    public void reportPlayback(String memberId, NeteasePlaybackRequest request, String clientIp, String netStatus) {
        boolean ending = "play".equals(request.action());
        if (ending && (request.playedSeconds() == null || request.playedSeconds() <= 0)) return;
        if (ending && !StringUtils.hasText(request.end())) throw new BadRequestException("结束播放时必须说明结束方式");
        Map<String, Object> biz = new LinkedHashMap<>();
        biz.put("id", request.songId());
        biz.put("action", request.action());
        biz.put("bitrate", request.bitrate());
        biz.put("file", 4);
        biz.put("type", "song");
        biz.put("startLogTime", request.startLogTime());
        if (ending) {
            biz.put("time", request.playedSeconds());
            biz.put("end", request.end());
        }
        if (StringUtils.hasText(request.alg())) biz.put("alg", request.alg());
        if (StringUtils.hasText(request.playlistId())) {
            biz.put("sourceId", request.playlistId());
            biz.put("sourceType", "list");
        }
        if (request.auditionStart() != null && request.auditionEnd() != null) {
            biz.put("isAudition", 1);
            biz.put("auditionStart", request.auditionStart());
            biz.put("auditionEnd", request.auditionEnd());
        }
        callAuthorized(connectedBinding(memberId), PLAYBACK_PATH, biz, clientIp, netStatus);
    }

    private JsonNode callAuthorized(NeteaseMusicBindingEntity binding, String path, Map<String, ?> biz,
            String clientIp, String netStatus) {
        refreshIfNeeded(binding, clientIp, netStatus);
        try {
            return client.call(path, accessToken(binding), binding.getDeviceId(), clientIp, netStatus, biz);
        } catch (NeteaseMusicApiException ex) {
            if (ex.code() != 1406) {
                if (ex.code() == 1407 || ex.code() == 1408) markReauth(binding, ex.getMessage());
                throw ex;
            }
            refresh(binding, clientIp, netStatus);
            return client.call(path, accessToken(binding), binding.getDeviceId(), clientIp, netStatus, biz);
        }
    }

    private void refreshIfNeeded(NeteaseMusicBindingEntity binding, String clientIp, String netStatus) {
        if (binding.getTokenExpiresAt() == null || binding.getTokenExpiresAt().isBefore(LocalDateTime.now().plusMinutes(5))) {
            refresh(binding, clientIp, netStatus);
        }
    }

    private void refresh(NeteaseMusicBindingEntity binding, String clientIp, String netStatus) {
        try {
            NeteaseMusicProperties properties = client.properties();
            JsonNode data = client.call(REFRESH_PATH, accessToken(binding), binding.getDeviceId(), clientIp,
                    netStatus, Map.of(
                    "clientId", properties.appId(), "clientSecret", properties.appSecret(),
                    "refreshToken", refreshToken(binding))).path("data");
            String access = required(data, "accessToken", "网易云刷新后未返回 accessToken");
            String refresh = required(data, "refreshToken", "网易云刷新后未返回 refreshToken");
            encryptTokens(binding, access, refresh);
            binding.setTokenExpiresAt(LocalDateTime.now().plusSeconds(Math.max(60, data.path("expiresTime").asLong(604800))));
            binding.setStatus(CONNECTED);
            binding.setLastError(null);
            binding.setUpdatedAt(LocalDateTime.now());
            bindingMapper.updateById(binding);
        } catch (NeteaseMusicApiException ex) {
            if (ex.code() == 1406 || ex.code() == 1407 || ex.code() == 1408) {
                markReauth(binding, "网易云授权已失效，请重新绑定");
                throw new BadRequestException("网易云授权已失效，请重新绑定");
            }
            throw ex;
        }
    }

    private void markReauth(NeteaseMusicBindingEntity binding, String message) {
        binding.setStatus(REAUTH_REQUIRED);
        binding.setLastError(safeError(message));
        binding.setUpdatedAt(LocalDateTime.now());
        bindingMapper.updateById(binding);
    }

    private NeteaseMusicBindingEntity connectedBinding(String memberId) {
        NeteaseMusicBindingEntity binding = bindingMapper.selectById(memberId);
        if (binding == null) throw new BadRequestException("请先绑定自己的网易云音乐账号");
        if (!CONNECTED.equals(binding.getStatus())) throw new BadRequestException("网易云授权已失效，请重新绑定");
        return binding;
    }

    private void encryptTokens(NeteaseMusicBindingEntity binding, String accessToken, String refreshToken) {
        VaultCryptoService.Encrypted access = crypto.encrypt(accessToken);
        VaultCryptoService.Encrypted refresh = crypto.encrypt(refreshToken);
        binding.setAccessTokenCiphertext(access.ciphertext());
        binding.setAccessTokenNonce(access.nonce());
        binding.setRefreshTokenCiphertext(refresh.ciphertext());
        binding.setRefreshTokenNonce(refresh.nonce());
    }

    private String accessToken(NeteaseMusicBindingEntity binding) {
        return crypto.decrypt(binding.getAccessTokenCiphertext(), binding.getAccessTokenNonce());
    }

    private String refreshToken(NeteaseMusicBindingEntity binding) {
        return crypto.decrypt(binding.getRefreshTokenCiphertext(), binding.getRefreshTokenNonce());
    }

    private static NeteaseMusicTrackDto toTrack(JsonNode song) {
        List<String> artists = new ArrayList<>();
        song.path("artists").forEach(artist -> artists.add(artist.path("name").asText("未知歌手")));
        boolean playable = song.path("playFlag").asBoolean(false) && song.path("visible").asBoolean(true);
        return new NeteaseMusicTrackDto(song.path("id").asText(), song.path("name").asText("未命名歌曲"),
                song.path("duration").asLong(), artists, song.path("album").path("name").asText(null),
                song.path("coverImgUrl").asText(null), playable, song.path("freeTrailFlag").asBoolean(false),
                song.path("alg").asText(null));
    }

    private static String required(JsonNode node, String field, String message) {
        String value = node.path(field).asText(null);
        if (!StringUtils.hasText(value)) throw new BadRequestException(message);
        return value;
    }

    private static String compactId() { return UUID.randomUUID().toString().replace("-", ""); }
    private static String safeError(String message) {
        if (!StringUtils.hasText(message)) return "网易云授权已失效，请重新绑定";
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
