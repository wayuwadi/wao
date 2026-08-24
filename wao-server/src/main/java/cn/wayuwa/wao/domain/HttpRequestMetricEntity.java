package cn.wayuwa.wao.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 浏览器测得的成员私有 HTTP 端到端请求耗时。 */
@TableName("http_request_metric")
public class HttpRequestMetricEntity {
    @TableId(type = IdType.AUTO) private Long id;
    private String memberId;
    private String method;
    private String requestPath;
    private Integer statusCode;
    private Integer durationMs;
    private String networkType;
    private Integer retryAttempt;
    private LocalDateTime startedAt;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public String getMemberId() { return memberId; }
    public void setMemberId(String value) { memberId = value; }
    public String getMethod() { return method; }
    public void setMethod(String value) { method = value; }
    public String getRequestPath() { return requestPath; }
    public void setRequestPath(String value) { requestPath = value; }
    public Integer getStatusCode() { return statusCode; }
    public void setStatusCode(Integer value) { statusCode = value; }
    public Integer getDurationMs() { return durationMs; }
    public void setDurationMs(Integer value) { durationMs = value; }
    public String getNetworkType() { return networkType; }
    public void setNetworkType(String value) { networkType = value; }
    public Integer getRetryAttempt() { return retryAttempt; }
    public void setRetryAttempt(Integer value) { retryAttempt = value; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime value) { startedAt = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime value) { createdAt = value; }
}
