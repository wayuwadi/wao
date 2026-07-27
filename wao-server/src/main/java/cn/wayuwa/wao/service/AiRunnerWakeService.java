package cn.wayuwa.wao.service;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.regex.Pattern;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.domain.AiRunnerNodeEntity;
import cn.wayuwa.wao.dto.UpdateRunnerWakeConfigRequest;
import cn.wayuwa.wao.dto.WakeRunnerResultDto;
import cn.wayuwa.wao.mapper.AiRunnerNodeMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 保存 Wake-on-LAN 参数并向离线执行电脑发送标准 Magic Packet。 */
@Service
public class AiRunnerWakeService {

    private static final Pattern MAC = Pattern.compile("(?i)^[0-9a-f]{2}([:-][0-9a-f]{2}){5}$");
    private static final Pattern HOST = Pattern.compile("^[A-Za-z0-9._:-]+$");
    private final AiRunnerNodeMapper runnerMapper;

    public AiRunnerWakeService(AiRunnerNodeMapper runnerMapper) {
        this.runnerMapper = runnerMapper;
    }

    /** 校验并保存一台执行电脑的网卡地址和唤醒目标。 */
    public void updateConfig(String runnerId, UpdateRunnerWakeConfigRequest request) {
        AiRunnerNodeEntity runner = requireRunner(runnerId);
        String mac = normalizeMac(request.mac());
        String host = request.host().trim();
        if (!HOST.matcher(host).matches()) throw new BadRequestException("唤醒地址只能填写 IP、域名或广播地址");
        runner.setWakeMac(mac);
        runner.setWakeHost(host);
        runner.setWakePort(request.port() == null ? 9 : request.port());
        runnerMapper.updateById(runner);
    }

    /** 发送三次标准 Magic Packet；是否真正开机仍由后续心跳确认。 */
    public WakeRunnerResultDto wake(String runnerId) {
        AiRunnerNodeEntity runner = requireRunner(runnerId);
        if (!StringUtils.hasText(runner.getWakeMac()) || !StringUtils.hasText(runner.getWakeHost())) {
            throw new BadRequestException("这台电脑尚未配置远程唤醒，请先填写 MAC 地址和唤醒目标");
        }
        byte[] packet = magicPacket(runner.getWakeMac());
        int port = runner.getWakePort() == null ? 9 : runner.getWakePort();
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            InetAddress address = InetAddress.getByName(runner.getWakeHost());
            DatagramPacket datagram = new DatagramPacket(packet, packet.length, address, port);
            for (int i = 0; i < 3; i++) socket.send(datagram);
        } catch (IOException error) {
            throw new BadRequestException("唤醒包发送失败：" + error.getMessage());
        }
        LocalDateTime now = LocalDateTime.now();
        runner.setLastWakeRequestedAt(now);
        runnerMapper.updateById(runner);
        return new WakeRunnerResultDto(runnerId, now.toString(), "唤醒包已发送，正在等待电脑恢复心跳");
    }

    static byte[] magicPacket(String mac) {
        byte[] address = HexFormat.of().parseHex(normalizeMac(mac).replace(":", ""));
        byte[] packet = new byte[6 + 16 * address.length];
        for (int i = 0; i < 6; i++) packet[i] = (byte) 0xff;
        for (int i = 0; i < 16; i++) System.arraycopy(address, 0, packet, 6 + i * address.length, address.length);
        return packet;
    }

    static String normalizeMac(String value) {
        String mac = value == null ? "" : value.trim();
        if (!MAC.matcher(mac).matches()) throw new BadRequestException("MAC 地址格式应为 AA:BB:CC:DD:EE:FF");
        return mac.replace('-', ':').toUpperCase();
    }

    private AiRunnerNodeEntity requireRunner(String runnerId) {
        AiRunnerNodeEntity runner = runnerMapper.selectById(runnerId);
        if (runner == null) throw new NotFoundException("执行电脑不存在：" + runnerId);
        return runner;
    }
}
