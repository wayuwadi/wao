using System.IO;
using System.Net;
using System.Security.Cryptography;
using System.Text;

namespace Wao.Windows;

internal sealed class SessionCookieStore
{
    private static readonly byte[] Entropy = Encoding.UTF8.GetBytes("cn.wayuwa.wao.windows.session.v1");
    private readonly Uri _apiBaseUri;
    private readonly string _cookiePath;

    public SessionCookieStore(Uri apiBaseUri)
    {
        _apiBaseUri = apiBaseUri;
        _cookiePath = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "WAO",
            "session.bin");
    }

    public void Restore(CookieContainer cookies)
    {
        try
        {
            if (!File.Exists(_cookiePath)) return;
            var protectedValue = File.ReadAllBytes(_cookiePath);
            var value = Encoding.UTF8.GetString(ProtectedData.Unprotect(
                protectedValue,
                Entropy,
                DataProtectionScope.CurrentUser));
            if (string.IsNullOrWhiteSpace(value)) return;
            cookies.Add(_apiBaseUri, new Cookie("WAO_SESSION", value, "/")
            {
                Secure = true,
                HttpOnly = true
            });
        }
        catch (CryptographicException)
        {
            Clear();
        }
        catch (IOException)
        {
            // 本机状态不可读时退回登录页，不让缓存错误阻止应用启动。
        }
    }

    public void Save(CookieContainer cookies)
    {
        var cookie = cookies.GetCookies(_apiBaseUri)["WAO_SESSION"];
        if (cookie is null || string.IsNullOrWhiteSpace(cookie.Value)) return;
        var protectedValue = ProtectedData.Protect(
            Encoding.UTF8.GetBytes(cookie.Value),
            Entropy,
            DataProtectionScope.CurrentUser);
        Directory.CreateDirectory(Path.GetDirectoryName(_cookiePath)!);
        File.WriteAllBytes(_cookiePath, protectedValue);
    }

    public void Clear()
    {
        try { File.Delete(_cookiePath); } catch (IOException) { }
    }
}
