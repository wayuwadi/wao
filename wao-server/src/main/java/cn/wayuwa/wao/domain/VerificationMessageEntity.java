package cn.wayuwa.wao.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 一条由手机转发器接收的短信；正文与验证码只以 AES-256-GCM 密文保存。 */
@TableName("verification_message")
public class VerificationMessageEntity {
    @TableId private String id;
    private String fingerprint;
    private String phoneNumber;
    private String sender;
    private String source;
    private byte[] messageCiphertext;
    private byte[] messageNonce;
    private byte[] codeCiphertext;
    private byte[] codeNonce;
    private LocalDateTime receivedAt;
    private String forwardStatus;
    private String forwardError;
    private LocalDateTime createdAt;

    public String getId() { return id; }
    public void setId(String value) { id = value; }
    public String getFingerprint() { return fingerprint; }
    public void setFingerprint(String value) { fingerprint = value; }
    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String value) { phoneNumber = value; }
    public String getSender() { return sender; }
    public void setSender(String value) { sender = value; }
    public String getSource() { return source; }
    public void setSource(String value) { source = value; }
    public byte[] getMessageCiphertext() { return messageCiphertext; }
    public void setMessageCiphertext(byte[] value) { messageCiphertext = value; }
    public byte[] getMessageNonce() { return messageNonce; }
    public void setMessageNonce(byte[] value) { messageNonce = value; }
    public byte[] getCodeCiphertext() { return codeCiphertext; }
    public void setCodeCiphertext(byte[] value) { codeCiphertext = value; }
    public byte[] getCodeNonce() { return codeNonce; }
    public void setCodeNonce(byte[] value) { codeNonce = value; }
    public LocalDateTime getReceivedAt() { return receivedAt; }
    public void setReceivedAt(LocalDateTime value) { receivedAt = value; }
    public String getForwardStatus() { return forwardStatus; }
    public void setForwardStatus(String value) { forwardStatus = value; }
    public String getForwardError() { return forwardError; }
    public void setForwardError(String value) { forwardError = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime value) { createdAt = value; }
}
