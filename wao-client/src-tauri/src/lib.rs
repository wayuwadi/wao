use tauri::{
    menu::{MenuBuilder, MenuItemBuilder},
    tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent},
    webview::{NewWindowResponse, PageLoadEvent, WebviewBuilder},
    Emitter, LogicalPosition, LogicalSize, Manager, Webview, WebviewUrl, WindowEvent,
};
use tauri_plugin_opener::OpenerExt;

const PRODUCTION: &str = "https://wao.wayuwa.cn";
const HEADER: f64 = 64.0;

fn workspace_url() -> tauri::Url {
    #[cfg(debug_assertions)]
    if let Ok(value) = std::env::var("WAO_WEB_DEV_URL") {
        if let Ok(url) = tauri::Url::parse(&value) {
            if url.scheme() == "http"
                && matches!(url.host_str(), Some("localhost" | "127.0.0.1" | "[::1]"))
            {
                return url;
            }
        }
    }
    tauri::Url::parse(PRODUCTION).unwrap()
}

fn internal_url(url: &tauri::Url, origin: &tauri::Url) -> bool {
    if url.origin() == origin.origin() {
        return true;
    }
    url.scheme() == "https" && matches!(url.host_str(), Some("accounts.feishu.cn" | "open.feishu.cn" | "passport.feishu.cn" | "accounts.larksuite.com" | "open.weixin.qq.com"))
}

fn external_url(url: &tauri::Url) -> bool {
    matches!(url.scheme(), "https" | "http") && url.username().is_empty() && url.password().is_none()
}

// Custom commands are also origin checked: remote workspace gets no native authority,
// even if a future capability accidentally broadens an allow list.
fn local_shell(view: &Webview) -> Result<(), String> {
    let url = view.url().map_err(|_| "无法验证调用来源")?;
    let origin_ok = url.scheme() == "tauri" || url.host_str() == Some("tauri.localhost")
        || (cfg!(debug_assertions) && url.host_str() == Some("localhost") && url.port() == Some(1430));
    if view.label() != "main" || !origin_ok { return Err("仅本地桌面外壳可执行此操作".into()); }
    Ok(())
}

fn show(app: &tauri::AppHandle) {
    if let Some(window) = app.get_window("main") {
        let _ = window.show();
        let _ = window.unminimize();
        let _ = window.set_focus();
    }
}

#[tauri::command]
async fn attach_workspace(app: tauri::AppHandle, webview: Webview) -> Result<(), String> {
    local_shell(&webview)?;
    if app.get_webview("workspace").is_some() { return Ok(()); }
    let window = app.get_window("main").ok_or("窗口不存在")?;
    let size = window.inner_size().map_err(|e| e.to_string())?.to_logical::<f64>(window.scale_factor().map_err(|e| e.to_string())?);
    let origin = workspace_url();
    let navigation_origin = origin.clone();
    let navigation_app = app.clone();
    let popup_app = app.clone();
    let popup_origin = origin.clone();
    let builder = WebviewBuilder::new("workspace", WebviewUrl::External(origin))
        .disable_drag_drop_handler()
        .on_navigation(move |url| {
            if internal_url(url, &navigation_origin) { return true; }
            if external_url(url) { let _ = navigation_app.opener().open_url(url.as_str(), None::<&str>); }
            false
        })
        .on_new_window(move |url, _| {
            if internal_url(&url, &popup_origin) {
                if let Some(workspace) = popup_app.get_webview("workspace") { let _ = workspace.navigate(url); }
            } else if external_url(&url) { let _ = popup_app.opener().open_url(url.as_str(), None::<&str>); }
            NewWindowResponse::Deny
        })
        .on_page_load(|view, event| {
            let phase = match event.event() { PageLoadEvent::Started => "loading", PageLoadEvent::Finished => "ready" };
            let _ = view.app_handle().emit_to("main", "workspace-state", phase);
        });
    window.add_child(builder, LogicalPosition::new(0.0, HEADER), LogicalSize::new(size.width, (size.height - HEADER).max(1.0)))
        .map_err(|e| e.to_string())?;
    Ok(())
}

#[tauri::command]
fn workspace_action(app: tauri::AppHandle, webview: Webview, action: String) -> Result<(), String> {
    local_shell(&webview)?;
    let workspace = app.get_webview("workspace").ok_or("工作区尚未连接")?;
    match action.as_str() {
        "back" => workspace.eval("history.back()"),
        "forward" => workspace.eval("history.forward()"),
        "reload" => workspace.reload(),
        "hide" => workspace.hide(),
        "show" => workspace.show(),
        "browser" => return app.opener().open_url(PRODUCTION, None::<&str>).map_err(|e| e.to_string()),
        _ => return Err("不支持的工作区操作".into()),
    }.map_err(|e| e.to_string())
}

#[tauri::command]
fn quit_client(app: tauri::AppHandle, webview: Webview) -> Result<(), String> {
    local_shell(&webview)?;
    app.exit(0);
    Ok(())
}

pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_process::init())
        .plugin(tauri_plugin_updater::Builder::new().build())
        .plugin(tauri_plugin_window_state::Builder::default()
            .with_state_flags(tauri_plugin_window_state::StateFlags::all() & !tauri_plugin_window_state::StateFlags::VISIBLE)
            .build())
        .invoke_handler(tauri::generate_handler![attach_workspace, workspace_action, quit_client])
        .on_window_event(|window, event| match event {
            WindowEvent::CloseRequested { api, .. } => { api.prevent_close(); let _ = window.hide(); },
            WindowEvent::Resized(size) => {
                if let Some(view) = window.app_handle().get_webview("workspace") {
                    let logical = size.to_logical::<f64>(window.scale_factor().unwrap_or(1.0));
                    let _ = view.set_position(LogicalPosition::new(0.0, HEADER));
                    let _ = view.set_size(LogicalSize::new(logical.width, (logical.height - HEADER).max(1.0)));
                }
            },
            _ => {}
        })
        .setup(|app| {
            let open = MenuItemBuilder::with_id("open", "打开 WAO 工作区").build(app)?;
            let update = MenuItemBuilder::with_id("update", "检查客户端更新").build(app)?;
            let quit = MenuItemBuilder::with_id("quit", "退出 WAO…").build(app)?;
            let menu = MenuBuilder::new(app).items(&[&open, &update]).separator().item(&quit).build()?;
            TrayIconBuilder::new().icon(app.default_window_icon().unwrap().clone())
                .tooltip("WAO · 你的桌面工作区").menu(&menu).show_menu_on_left_click(false)
                .on_menu_event(|app, event| {
                    show(app);
                    match event.id.as_ref() {
                        "update" => { let _ = app.emit_to("main", "shell-action", "update"); },
                        "quit" => { let _ = app.emit_to("main", "shell-action", "quit"); },
                        _ => {}
                    }
                })
                .on_tray_icon_event(|tray, event| {
                    if let TrayIconEvent::Click { button: MouseButton::Left, button_state: MouseButtonState::Up, .. } = event { show(tray.app_handle()); }
                }).build()?;
            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("WAO client failed to start");
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn navigation_keeps_only_wao_and_known_auth_origins() {
        let origin = tauri::Url::parse(PRODUCTION).unwrap();
        for url in ["https://wao.wayuwa.cn/projects/1", "https://accounts.feishu.cn/open-apis/authen/v1/authorize"] { assert!(internal_url(&tauri::Url::parse(url).unwrap(), &origin)); }
        for url in ["https://wao.wayuwa.cn.evil.test", "http://wao.wayuwa.cn", "https://accounts.feishu.cn.evil.test", "file:///C:/secret", "javascript:alert(1)"] { assert!(!internal_url(&tauri::Url::parse(url).unwrap(), &origin)); }
    }
    #[test]
    fn external_links_cannot_execute_local_protocols() {
        for url in ["file:///C:/Windows", "ms-settings:privacy", "powershell:run", "javascript:alert(1)", "https://user:password@example.com"] { assert!(!external_url(&tauri::Url::parse(url).unwrap())); }
        assert!(external_url(&tauri::Url::parse("https://example.com/doc").unwrap()));
    }
}
