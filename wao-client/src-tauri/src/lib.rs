use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use tauri::{
    menu::{MenuBuilder, MenuItemBuilder},
    tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent},
    webview::{NewWindowResponse, PageLoadEvent, WebviewBuilder},
    Emitter, LogicalPosition, LogicalSize, Manager, Webview, WebviewUrl, WindowEvent,
};
use tauri_plugin_opener::OpenerExt;

const PRODUCTION: &str = "https://wao.wayuwa.cn";
const HEADER: f64 = 40.0;
static POPUP_ID: AtomicUsize = AtomicUsize::new(1);
static PET_STARTED: AtomicBool = AtomicBool::new(false);
static WORKSPACE_READY: AtomicBool = AtomicBool::new(false);

// A minimized Windows window reports a transient viewport (often 0 × 0).
// Keep the child WebView's last usable size so responsive content does not
// switch to its mobile layout and animate back when the window is restored.
fn workspace_size(size: LogicalSize<f64>, minimized: bool) -> Option<LogicalSize<f64>> {
    if minimized || size.width <= 0.0 || size.height <= HEADER {
        return None;
    }
    Some(LogicalSize::new(size.width, size.height - HEADER))
}

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
    url.scheme() == "https"
        && matches!(
            url.host_str(),
            Some(
                "accounts.feishu.cn"
                    | "open.feishu.cn"
                    | "passport.feishu.cn"
                    | "accounts.larksuite.com"
                    | "open.weixin.qq.com"
            )
        )
}

fn external_url(url: &tauri::Url) -> bool {
    matches!(url.scheme(), "https" | "http")
        && url.username().is_empty()
        && url.password().is_none()
}

// Custom commands are also origin checked: remote workspace gets no native authority,
// even if a future capability accidentally broadens an allow list.
fn local_shell(view: &Webview) -> Result<(), String> {
    let url = view.url().map_err(|_| "无法验证调用来源")?;
    let origin_ok = url.scheme() == "tauri"
        || url.host_str() == Some("tauri.localhost")
        || (cfg!(debug_assertions)
            && url.host_str() == Some("localhost")
            && url.port() == Some(1430));
    if view.label() != "main" || !origin_ok {
        return Err("仅本地桌面外壳可执行此操作".into());
    }
    Ok(())
}

fn show(app: &tauri::AppHandle) {
    if let Some(window) = app.get_window("main") {
        let _ = window.show();
        let _ = window.unminimize();
        let _ = window.set_focus();
    }
}

#[cfg(target_os = "macos")]
fn macos_menu(app: &tauri::App) -> tauri::Result<()> {
    use tauri::menu::SubmenuBuilder;
    let quit = MenuItemBuilder::with_id("mac-quit", "退出 WAO…")
        .accelerator("CmdOrCtrl+Q")
        .build(app)?;
    let application = SubmenuBuilder::new(app, "WAO")
        .text("mac-open", "打开 WAO 工作区")
        .text("mac-update", "检查客户端更新")
        .separator()
        .hide()
        .hide_others()
        .show_all()
        .separator()
        .item(&quit)
        .build()?;
    let edit = SubmenuBuilder::new(app, "编辑")
        .undo()
        .redo()
        .separator()
        .cut()
        .copy()
        .paste()
        .select_all()
        .build()?;
    let close = MenuItemBuilder::with_id("mac-close", "收起工作区")
        .accelerator("CmdOrCtrl+W")
        .build(app)?;
    let window = SubmenuBuilder::new(app, "窗口")
        .minimize()
        .item(&close)
        .text("mac-open", "打开 WAO 工作区")
        .build()?;
    app.set_menu(
        MenuBuilder::new(app)
            .items(&[&application, &edit, &window])
            .build()?,
    )?;
    app.on_menu_event(|app, event| match event.id.as_ref() {
        "mac-open" => show(app),
        "mac-close" => {
            if let Some(window) = app.get_window("main") {
                let _ = window.hide();
            }
        }
        "mac-update" | "mac-quit" => {
            show(app);
            let action = if event.id.as_ref() == "mac-update" {
                "update"
            } else {
                "quit"
            };
            let _ = app.emit_to("main", "shell-action", action);
        }
        _ => {}
    });
    Ok(())
}

#[cfg(any(target_os = "macos", test))]
fn download_url_allowed(url: &tauri::Url, origin: &tauri::Url) -> bool {
    url.origin() == origin.origin()
        && (matches!(url.scheme(), "https" | "blob")
            || (cfg!(debug_assertions) && url.scheme() == "http"))
}

#[cfg(any(target_os = "macos", test))]
fn download_path(directory: &std::path::Path, suggested: &std::path::Path) -> std::path::PathBuf {
    let name = suggested
        .file_name()
        .unwrap_or_else(|| std::ffi::OsStr::new("WAO-download"));
    let mut path = directory.join(name);
    let stem = path
        .file_stem()
        .unwrap_or_default()
        .to_string_lossy()
        .into_owned();
    let extension = path
        .extension()
        .map(|value| format!(".{}", value.to_string_lossy()))
        .unwrap_or_default();
    let mut number = 1;
    while path.symlink_metadata().is_ok() {
        path = directory.join(format!("{stem} ({number}){extension}"));
        number += 1;
    }
    path
}

#[cfg(target_os = "macos")]
fn handle_download(app: &tauri::AppHandle, event: tauri::webview::DownloadEvent<'_>) -> bool {
    use tauri::webview::DownloadEvent;
    let message = match event {
        DownloadEvent::Requested { url, destination } => {
            if !download_url_allowed(&url, &workspace_url()) {
                let _ = app.emit_to(
                    "main",
                    "workspace-download",
                    "该下载链接暂不支持，请在浏览器打开。",
                );
                return false;
            }
            let Ok(directory) = app.path().download_dir() else {
                let _ = app.emit_to(
                    "main",
                    "workspace-download",
                    "无法找到下载文件夹，请在浏览器重试。",
                );
                return false;
            };
            if std::fs::create_dir_all(&directory).is_err() {
                let _ = app.emit_to(
                    "main",
                    "workspace-download",
                    "无法访问下载文件夹，请检查权限后重试。",
                );
                return false;
            }
            *destination = download_path(&directory, destination);
            "正在保存到下载文件夹…"
        }
        DownloadEvent::Finished { success: true, .. } => "文件已保存到下载文件夹。",
        DownloadEvent::Finished { success: false, .. } => "文件下载失败，请重新点击下载。",
        _ => return false,
    };
    let _ = app.emit_to("main", "workspace-download", message);
    true
}

fn popup(
    app: &tauri::AppHandle,
    url: tauri::Url,
    features: tauri::webview::NewWindowFeatures,
) -> NewWindowResponse<tauri::Wry> {
    let origin = workspace_url();
    if url.as_str() != "about:blank" && !internal_url(&url, &origin) {
        if external_url(&url) {
            let _ = app.opener().open_url(url.as_str(), None::<&str>);
        }
        return NewWindowResponse::Deny;
    }
    let navigation_app = app.clone();
    let popup_app = app.clone();
    let label = format!("document-{}", POPUP_ID.fetch_add(1, Ordering::Relaxed));
    // Preserve window.open and shared WebView session without granting native permissions.
    let builder = tauri::WebviewWindowBuilder::new(
        app,
        label,
        WebviewUrl::External("about:blank".parse().unwrap()),
    )
    .window_features(features)
    .title("WAO · 新窗口")
    .inner_size(1100.0, 760.0)
    .on_navigation(move |next| {
        if next.as_str() == "about:blank" || internal_url(next, &origin) {
            return true;
        }
        if external_url(next) {
            let _ = navigation_app
                .opener()
                .open_url(next.as_str(), None::<&str>);
        }
        false
    })
    .on_new_window(move |next, features| popup(&popup_app, next, features))
    .on_document_title_changed(|window, title| {
        let _ = window.set_title(&format!("WAO · {title}"));
    });
    #[cfg(target_os = "macos")]
    let builder = builder.on_download(|view, event| handle_download(view.app_handle(), event));
    let result = builder.build();
    match result {
        Ok(window) => NewWindowResponse::Create { window },
        Err(_) => NewWindowResponse::Deny,
    }
}

// The remote pet can request only these fixed UI actions. No arbitrary URLs,
// filesystem paths, JavaScript, credentials, or native capabilities cross this boundary.
fn pet_action_name(url: &tauri::Url, origin: &tauri::Url) -> Option<String> {
    if url.origin() != origin.origin() || url.query().is_some() || url.fragment().is_some() {
        return None;
    }
    let action = url.path().strip_prefix("/__desktop_pet__/")?;
    if let Some(id) = action.strip_prefix("task/") {
        if !id.is_empty()
            && id.len() <= 100
            && id
                .bytes()
                .all(|c| c.is_ascii_alphanumeric() || c == b'-' || c == b'_')
        {
            return Some(action.to_string());
        }
    }
    if matches!(
        action,
        "compact" | "bubble" | "chat" | "hide" | "workspace" | "toggle-workspace" | "drag" | "open-chat"
    ) {
        Some(action.to_string())
    } else {
        None
    }
}

// Values crossing from the remote page are bounded display data only.
fn toolbar_state(url: &tauri::Url) -> serde_json::Value {
    let mut state = serde_json::json!({"ready": false, "chat": "0", "notifications": "0", "theme": "light"});
    for (key, value) in url.query_pairs() {
        match key.as_ref() {
            "theme" if value == "light" || value == "dark" => state["theme"] = serde_json::json!(value),
            "ready" => state["ready"] = serde_json::json!(value == "1"),
            "chat" | "notifications" if value == "99+" || value.parse::<u8>().is_ok_and(|n| n <= 99) => {
                state[key.as_ref()] = serde_json::json!(value);
            },
            _ => {},
        }
    }
    state
}

fn resize_pet(window: &tauri::WebviewWindow, width: f64, height: f64) {
    // Keep the bottom-right anchor on expansion, and clamp to the current work area.
    if let (Ok(position), Ok(size), Ok(scale), Ok(Some(monitor))) = (
        window.outer_position(),
        window.outer_size(),
        window.scale_factor(),
        window.current_monitor(),
    ) {
        let area = monitor.work_area();
        let width = width.min(area.size.width as f64 / scale);
        let height = height.min(area.size.height as f64 / scale);
        let x = (position.x + size.width as i32 - (width * scale) as i32)
            .max(area.position.x)
            .min(area.position.x + area.size.width as i32 - (width * scale) as i32);
        let y = (position.y + size.height as i32 - (height * scale) as i32)
            .max(area.position.y)
            .min(area.position.y + area.size.height as i32 - (height * scale) as i32);
        let _ = window.set_size(LogicalSize::new(width, height));
        let _ = window.set_position(tauri::PhysicalPosition::new(x, y));
    }
}

fn show_desktop_pet(app: &tauri::AppHandle) -> tauri::Result<()> {
    if let Some(window) = app.get_webview_window("pet") {
        window.show()?;
        return Ok(());
    }
    let origin = workspace_url();
    let url = origin
        .join("/desktop-pet?native=3")
        .expect("fixed pet route");
    let navigation_app = app.clone();
    let window = tauri::WebviewWindowBuilder::new(app, "pet", WebviewUrl::External(url))
        .title("WAO · 宠物传话")
        .inner_size(120.0, 120.0)
        .decorations(false)
        .transparent(true)
        .shadow(false)
        .always_on_top(true)
        .skip_taskbar(true)
        .resizable(false)
        .disable_drag_drop_handler()
        .on_navigation(move |next| {
            if let Some(action) = pet_action_name(next, &origin) {
                if let Some(pet) = navigation_app.get_webview_window("pet") {
                    // Requests must originate from this same-origin pet page.
                    if pet
                        .url()
                        .map(|current| {
                            current.origin() == origin.origin() && current.path() == "/desktop-pet"
                        })
                        .unwrap_or(false)
                    {
                        match action.as_str() {
                            "compact" => resize_pet(&pet, 120.0, 120.0),
                            "bubble" => resize_pet(&pet, 260.0, 190.0),
                            "chat" => resize_pet(&pet, 620.0, 780.0),
                            "hide" => {
                                let _ = pet.hide();
                            }
                            "workspace" => show(&navigation_app),
                            "toggle-workspace" => {
                                if let Some(main) = navigation_app.get_window("main") {
                                    if main.is_visible().unwrap_or(false) && !main.is_minimized().unwrap_or(false) {
                                        let _ = main.hide();
                                    } else {
                                        show(&navigation_app);
                                        let _ = navigation_app.emit_to("main", "shell-action", "workspace");
                                        if let Some(workspace) = navigation_app.get_webview("workspace") {
                                            let _ = workspace.show();
                                        }
                                    }
                                }
                            },
                            "open-chat" => {
                                show(&navigation_app);
                                let _ = navigation_app.emit_to("main", "shell-action", "workspace");
                                if let Some(workspace) = navigation_app.get_webview("workspace") {
                                    let _ = workspace.show();
                                    let _ = workspace.eval("window.dispatchEvent(new Event('wao-open-chat'))");
                                }
                            },
                            "drag" => {
                                let _ = pet.start_dragging();
                            }
                            _ => {
                                if let Some(id) = action.strip_prefix("task/") {
                                    show(&navigation_app);
                                    let _ = navigation_app.emit_to("main", "shell-action", "workspace");
                                    if let Some(workspace) = navigation_app.get_webview("workspace") {
                                        let _ = workspace.show();
                                        let detail = serde_json::to_string(id).unwrap_or_default();
                                        let _ = workspace.eval(format!("window.dispatchEvent(new CustomEvent('wao-open-task', {{ detail: {detail} }}))"));
                                    }
                                }
                            }
                        }
                    }
                }
                return false;
            }
            if next.origin() == origin.origin() && next.path() == "/desktop-pet" {
                return true;
            }
            if next.origin() == origin.origin() && next.path() == "/login" {
                show(&navigation_app);
                return false;
            }
            if external_url(next) {
                let _ = navigation_app
                    .opener()
                    .open_url(next.as_str(), None::<&str>);
            }
            false
        })
        .on_new_window(|_, _| NewWindowResponse::Deny)
        .build()?;
    if let Ok(Some(monitor)) = window.current_monitor() {
        let area = monitor.work_area();
        let scale = monitor.scale_factor();
        window.set_position(tauri::PhysicalPosition::new(
            area.position.x + area.size.width as i32 - (204.0 * scale) as i32,
            area.position.y + area.size.height as i32 - (214.0 * scale) as i32,
        ))?;
    }
    Ok(())
}

#[tauri::command]
async fn attach_workspace(app: tauri::AppHandle, webview: Webview) -> Result<(), String> {
    local_shell(&webview)?;
    if app.get_webview("workspace").is_some() {
        let phase = if WORKSPACE_READY.load(Ordering::Relaxed) {
            "ready"
        } else {
            "loading"
        };
        let _ = app.emit_to("main", "workspace-state", phase);
        return Ok(());
    }
    let window = app.get_window("main").ok_or("窗口不存在")?;
    let size = window
        .inner_size()
        .map_err(|e| e.to_string())?
        .to_logical::<f64>(window.scale_factor().map_err(|e| e.to_string())?);
    let origin = workspace_url();
    let navigation_origin = origin.clone();
    let navigation_app = app.clone();
    let popup_app = app.clone();
    let builder = WebviewBuilder::new("workspace", WebviewUrl::External(origin))
        .disable_drag_drop_handler()
        .initialization_script("Object.defineProperty(window, '__WAO_DESKTOP__', { value: true }); Object.defineProperty(window, '__WAO_DESKTOP_TOOLBAR__', { value: true });")
        .on_navigation(move |url| {
            if url.origin() == navigation_origin.origin() && url.path() == "/__desktop_toolbar__" {
                if navigation_app.get_webview("workspace").and_then(|view| view.url().ok())
                    .is_some_and(|current| current.origin() == navigation_origin.origin()) {
                    let state = toolbar_state(url);
                    let _ = navigation_app.emit_to("main", "toolbar-state", state);
                }
                return false;
            }
            if internal_url(url, &navigation_origin) {
                return true;
            }
            if external_url(url) {
                let _ = navigation_app.opener().open_url(url.as_str(), None::<&str>);
            }
            false
        })
        .on_new_window(move |url, features| popup(&popup_app, url, features))
        .on_page_load(|view, event| {
            WORKSPACE_READY.store(
                matches!(event.event(), PageLoadEvent::Finished),
                Ordering::Relaxed,
            );
            let phase = match event.event() {
                PageLoadEvent::Started => "loading",
                PageLoadEvent::Finished => "ready",
            };
            let _ = view.app_handle().emit_to("main", "workspace-state", phase);
            if matches!(event.event(), PageLoadEvent::Started) {
                let _ = view.app_handle().emit_to("main", "toolbar-state", serde_json::json!({"ready": false, "chat": "0", "notifications": "0", "theme": "light"}));
            }
            if matches!(event.event(), PageLoadEvent::Finished)
                && !PET_STARTED.swap(true, Ordering::Relaxed)
            {
                let app = view.app_handle().clone();
                tauri::async_runtime::spawn_blocking(move || {
                    if let Err(error) = show_desktop_pet(&app) {
                        PET_STARTED.store(false, Ordering::Relaxed);
                        eprintln!("Could not open desktop pet: {error}");
                    }
                });
            }
        });
    #[cfg(target_os = "macos")]
    let builder = builder.on_download(|view, event| handle_download(view.app_handle(), event));
    window
        .add_child(
            builder,
            LogicalPosition::new(0.0, HEADER),
            LogicalSize::new(size.width, (size.height - HEADER).max(1.0)),
        )
        .map_err(|e| e.to_string())?;
    Ok(())
}

#[tauri::command]
async fn workspace_action(
    app: tauri::AppHandle,
    webview: Webview,
    action: String,
) -> Result<(), String> {
    local_shell(&webview)?;
    let workspace = app.get_webview("workspace").ok_or("工作区尚未连接")?;
    match action.as_str() {
        "pet" => {
            if let Some(pet) = app.get_webview_window("pet") {
                if pet.is_visible().map_err(|e| e.to_string())? {
                    return pet.hide().map_err(|e| e.to_string());
                }
            }
            return show_desktop_pet(&app).map_err(|e| e.to_string());
        },
        "new-task" | "new-note" | "theme" | "shortcuts" | "chat" | "notifications" => {
            if workspace.url().map_err(|e| e.to_string())?.origin() != workspace_url().origin() {
                return Err("当前页面不支持工作区快捷操作".into());
            }
            // Only the matched fixed selector reaches eval; no caller-supplied code is accepted.
            workspace.eval(format!("document.querySelector('[data-desktop-action=\"{}\"]')?.click()", action))
        },
        "back" => workspace.eval("history.back()"),
        "forward" => workspace.eval("history.forward()"),
        "reload" => workspace.eval("window.dispatchEvent(new Event('wao-workspace-reload'))"),
        "hide" => workspace.hide(),
        "show" => workspace.show(),
        "browser" => {
            return app
                .opener()
                .open_url(PRODUCTION, None::<&str>)
                .map_err(|e| e.to_string())
        }
        _ => return Err("不支持的工作区操作".into()),
    }
    .map_err(|e| e.to_string())
}

#[tauri::command]
fn quit_client(app: tauri::AppHandle, webview: Webview) -> Result<(), String> {
    local_shell(&webview)?;
    app.exit(0);
    Ok(())
}

pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_single_instance::init(|app, _, _| show(app)))
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_process::init())
        .plugin(tauri_plugin_updater::Builder::new().build())
        .plugin(
            tauri_plugin_window_state::Builder::default()
                .with_state_flags(
                    tauri_plugin_window_state::StateFlags::all()
                        & !tauri_plugin_window_state::StateFlags::VISIBLE,
                )
                .build(),
        )
        .invoke_handler(tauri::generate_handler![
            attach_workspace,
            workspace_action,
            quit_client
        ])
        .on_window_event(|window, event| {
            if window.label() != "main" {
                return;
            }
            match event {
                WindowEvent::CloseRequested { api, .. } => {
                    api.prevent_close();
                    let _ = window.hide();
                }
                WindowEvent::Resized(size) => {
                    let logical = size.to_logical::<f64>(window.scale_factor().unwrap_or(1.0));
                    if let Some(size) =
                        workspace_size(logical, window.is_minimized().unwrap_or(false))
                    {
                        if let Some(view) = window.app_handle().get_webview("workspace") {
                            // Refresh the logical header offset together with the size;
                            // the old physical position can be stale after a DPI change.
                            let _ = view.set_bounds(tauri::Rect {
                                position: LogicalPosition::new(0.0, HEADER).into(),
                                size: size.into(),
                            });
                        }
                    }
                }
                _ => {}
            }
        })
        .setup(|app| {
            show(app.handle());
            #[cfg(target_os = "macos")]
            macos_menu(app)?;
            let open = MenuItemBuilder::with_id("open", "打开 WAO 工作区").build(app)?;
            let pet = MenuItemBuilder::with_id("pet", "显示/隐藏宠物").build(app)?;
            let update = MenuItemBuilder::with_id("update", "检查客户端更新").build(app)?;
            let quit = MenuItemBuilder::with_id("quit", "退出 WAO…").build(app)?;
            let menu = MenuBuilder::new(app)
                .items(&[&open, &pet, &update])
                .separator()
                .item(&quit)
                .build()?;
            TrayIconBuilder::new()
                .icon(app.default_window_icon().unwrap().clone())
                .tooltip("WAO · 你的桌面工作区")
                .menu(&menu)
                .show_menu_on_left_click(cfg!(target_os = "macos"))
                .on_menu_event(|app, event| {
                    match event.id.as_ref() {
                        "pet" => {
                            // WebView2 window creation can deadlock inside a Windows event handler.
                            let handle = app.clone();
                            tauri::async_runtime::spawn_blocking(move || {
                                if let Some(pet) = handle.get_webview_window("pet") {
                                    if pet.is_visible().unwrap_or(false) {
                                        let _ = pet.hide();
                                        return;
                                    }
                                }
                                if let Err(error) = show_desktop_pet(&handle) {
                                    eprintln!("Could not open desktop pet: {error}");
                                }
                            });
                        }
                        "update" => {
                            show(app);
                            let _ = app.emit_to("main", "shell-action", "update");
                        }
                        "quit" => {
                            show(app);
                            let _ = app.emit_to("main", "shell-action", "quit");
                        }
                        _ => show(app),
                    }
                })
                .on_tray_icon_event(|tray, event| {
                    if let TrayIconEvent::Click {
                        button: MouseButton::Left,
                        button_state: MouseButtonState::Up,
                        ..
                    } = event
                    {
                        show(tray.app_handle());
                    }
                })
                .build(app)?;
            Ok(())
        })
        .build(tauri::generate_context!())
        .expect("WAO client failed to start")
        .run(|_app, event| {
            #[cfg(target_os = "macos")]
            match event {
                tauri::RunEvent::Reopen { .. } => show(_app),
                // System/Dock quit has no exit code. Explicit confirmed quit and
                // updater restarts carry a code and must remain able to complete.
                tauri::RunEvent::ExitRequested {
                    code: None, api, ..
                } => {
                    api.prevent_exit();
                    show(_app);
                    let _ = _app.emit_to("main", "shell-action", "quit");
                }
                _ => {}
            }
            #[cfg(not(target_os = "macos"))]
            let _ = event;
        });
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn minimize_restore_keeps_workspace_viewport_and_normal_resizing() {
        let initial = LogicalSize::new(1380.0, 860.0);
        let mut viewport = initial;
        for (size, minimized) in [
            (LogicalSize::new(0.0, 0.0), true),
            (LogicalSize::new(160.0, 28.0), true),
            (LogicalSize::new(960.0, 700.0), true),
            (LogicalSize::new(0.0, 900.0), false),
            (LogicalSize::new(1380.0, HEADER), false),
        ] {
            if let Some(next) = workspace_size(size, minimized) {
                viewport = next;
            }
            assert_eq!(viewport, initial);
        }
        assert_eq!(
            workspace_size(LogicalSize::new(1380.0, 900.0), false),
            Some(initial)
        );
        // A real resize (including a DPI-converted width) must still reach the WebView.
        assert_eq!(
            workspace_size(tauri::PhysicalSize::new(1200, 840).to_logical(1.5), false),
            Some(LogicalSize::new(800.0, 520.0))
        );
    }
    #[test]
    fn macos_downloads_keep_the_origin_and_cannot_overwrite_or_escape_downloads() {
        let origin = tauri::Url::parse(PRODUCTION).unwrap();
        assert!(download_url_allowed(
            &tauri::Url::parse("https://wao.wayuwa.cn/api/files/1").unwrap(),
            &origin
        ));
        assert!(download_url_allowed(
            &tauri::Url::parse("blob:https://wao.wayuwa.cn/1234").unwrap(),
            &origin
        ));
        for url in [
            "blob:https://evil.test/1234",
            "https://evil.test/file",
            "file:///etc/passwd",
            "data:text/plain,secret",
        ] {
            assert!(!download_url_allowed(
                &tauri::Url::parse(url).unwrap(),
                &origin
            ));
        }
        let directory =
            std::env::temp_dir().join(format!("wao-download-test-{}", std::process::id()));
        std::fs::create_dir_all(&directory).unwrap();
        std::fs::write(directory.join("report.txt"), "existing content").unwrap();
        let chosen = download_path(&directory, std::path::Path::new("../../report.txt"));
        assert_eq!(chosen, directory.join("report (1).txt"));
        assert_eq!(
            std::fs::read_to_string(directory.join("report.txt")).unwrap(),
            "existing content"
        );
        std::fs::remove_dir_all(directory).unwrap();
    }
    #[test]
    fn navigation_keeps_only_wao_and_known_auth_origins() {
        let origin = tauri::Url::parse(PRODUCTION).unwrap();
        for url in [
            "https://wao.wayuwa.cn/projects/1",
            "https://accounts.feishu.cn/open-apis/authen/v1/authorize",
        ] {
            assert!(internal_url(&tauri::Url::parse(url).unwrap(), &origin));
        }
        for url in [
            "https://wao.wayuwa.cn.evil.test",
            "http://wao.wayuwa.cn",
            "https://accounts.feishu.cn.evil.test",
            "file:///C:/secret",
            "javascript:alert(1)",
        ] {
            assert!(!internal_url(&tauri::Url::parse(url).unwrap(), &origin));
        }
    }
    #[test]
    fn toolbar_only_forwards_bounded_display_state() {
        let url = tauri::Url::parse("https://wao.wayuwa.cn/__desktop_toolbar__?ready=1&chat=3&notifications=99%2B&script=ignored").unwrap();
        assert_eq!(toolbar_state(&url), serde_json::json!({"ready": true, "chat": "3", "notifications": "99+", "theme": "light"}));
        let url = tauri::Url::parse("https://wao.wayuwa.cn/__desktop_toolbar__?ready=wrong&chat=-1&notifications=1000").unwrap();
        assert_eq!(toolbar_state(&url), serde_json::json!({"ready": false, "chat": "0", "notifications": "0", "theme": "light"}));
        let dark = tauri::Url::parse("https://wao.wayuwa.cn/__desktop_toolbar__?theme=dark").unwrap();
        assert_eq!(toolbar_state(&dark)["theme"], "dark");
        let invalid = tauri::Url::parse("https://wao.wayuwa.cn/__desktop_toolbar__?theme=arbitrary").unwrap();
        assert_eq!(toolbar_state(&invalid)["theme"], "light");
        let origin = tauri::Url::parse(PRODUCTION).unwrap();
        assert_eq!(pet_action_name(&origin.join("/__desktop_pet__/toggle-workspace").unwrap(), &origin).as_deref(), Some("toggle-workspace"));
        assert!(pet_action_name(&tauri::Url::parse("https://evil.test/__desktop_pet__/toggle-workspace").unwrap(), &origin).is_none());
    }

    #[test]
    fn pet_only_accepts_fixed_same_origin_actions() {
        let origin = tauri::Url::parse(PRODUCTION).unwrap();
        assert_eq!(
            pet_action_name(&origin.join("/__desktop_pet__/chat").unwrap(), &origin).as_deref(),
            Some("chat")
        );
        assert_eq!(
            pet_action_name(&origin.join("/__desktop_pet__/open-chat").unwrap(), &origin)
                .as_deref(),
            Some("open-chat")
        );
        assert_eq!(
            pet_action_name(&origin.join("/__desktop_pet__/task/t123").unwrap(), &origin)
                .as_deref(),
            Some("task/t123")
        );
        for url in [
            "https://evil.test/__desktop_pet__/chat",
            "https://wao.wayuwa.cn/__desktop_pet__/eval",
            "https://wao.wayuwa.cn/__desktop_pet__/task/a%27b",
            "https://wao.wayuwa.cn/__desktop_pet__/task/a/b",
            "https://wao.wayuwa.cn/__desktop_pet__/chat?url=file:///etc/passwd",
        ] {
            assert!(pet_action_name(&tauri::Url::parse(url).unwrap(), &origin).is_none());
        }
    }

    #[test]
    fn external_links_cannot_execute_local_protocols() {
        for url in [
            "file:///C:/Windows",
            "ms-settings:privacy",
            "powershell:run",
            "javascript:alert(1)",
            "https://user:password@example.com",
        ] {
            assert!(!external_url(&tauri::Url::parse(url).unwrap()));
        }
        assert!(external_url(
            &tauri::Url::parse("https://example.com/doc").unwrap()
        ));
    }
}
