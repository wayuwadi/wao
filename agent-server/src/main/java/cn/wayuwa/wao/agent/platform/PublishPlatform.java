package cn.wayuwa.wao.agent.platform;

/**
 * 自媒体平台适配器。新增平台只需实现本接口并注册为 Bean，主链路不需要改动。
 *
 * <p>平台开放程度差异很大，因此能力由 {@link #capability()} 显式声明：没有官方发布接口的平台
 * 必须把 publishSupported 声明为 false，并在 {@link #publish} 中抛出说明性异常，不得伪造成功。
 */
public interface PublishPlatform {

    PlatformType type();

    /** 当前配置下真实具备的能力，受凭据是否配置影响。 */
    PlatformCapability capability();

    /** 构造平台授权页地址；state 用于回调防伪。 */
    String authorizeUrl(String state);

    /** 用授权码换取凭据。 */
    PlatformIdentity exchangeCode(String code);

    /** 刷新访问令牌。 */
    PlatformIdentity refresh(PlatformCredential credential);

    /** 执行真实发布。 */
    PublishResult publish(PlatformCredential credential, PublishRequest request);
}
