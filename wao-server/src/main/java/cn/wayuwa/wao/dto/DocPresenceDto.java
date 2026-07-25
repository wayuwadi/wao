package cn.wayuwa.wao.dto;

import java.util.List;

/**
 * 一次在场心跳的应答。
 *
 * @param peers 除自己以外、此刻也打开着这篇文档的人
 * @param rev   服务端当前正文版本号
 * @param doc   仅当客户端 rev 落后时返回的最新文档，否则为 null（避免每次心跳都拖回整篇正文）
 */
public record DocPresenceDto(
        List<DocPeerDto> peers,
        Integer rev,
        DocDto doc) {
}
