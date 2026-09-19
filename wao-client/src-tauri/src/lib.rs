use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use tauri::{
    menu::{CheckMenuItemBuilder, MenuBuilder, MenuItemBuilder},
    tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent},
    webview::{NewWindowResponse, PageLoadEvent, WebviewBuilder},
    Emitter, LogicalPosition, LogicalSize, Manager, Webview, WebviewUrl, WindowEvent,
};
use tauri_plugin_opener::OpenerExt;

const PRODUCTION: &str = "https://wao.wayuwa.cn";
const HEADER: f64 = 40.0;
const DESKTOP_SIGNAL_PREFIX: &str = "WAO_DESKTOP_SIGNAL:";
const MAX_DESKTOP_SIGNAL_BYTES: usize = 32 * 1024;
static POPUP_ID: AtomicUsize = AtomicUsize::new(1);
static PET_STARTED: AtomicBool = AtomicBool::new(false);
static TRAY_UNREAD: AtomicBool = AtomicBool::new(false);
static TRAY_FLASH_ENABLED: AtomicBool = AtomicBool::new(true);
static WORKSPACE_READY: AtomicBool = AtomicBool::new(false);

// Only bounded unread counts already carried by the trusted workspace bridge are used.
fn has_unread(state: &serde_json::Value) -> bool {
    state["ready"] == true && ["chat", "notifications"].iter().any(|key| {
        state[*key].as_str().is_some_and(|value| value == "99+" || value.parse::<u8>().is_ok_and(|n| n > 0 && n <= 99))
    })
}

fn tray_preference_path(app: &tauri::AppHandle) -> Option<std::path::PathBuf> {
    app.path().app_config_dir().ok().map(|path| path.join("tray-flash.json"))
}

#[cfg(target_os = "windows")]
fn start_tray_flash(app: tauri::AppHandle) {
    let icon = app.default_window_icon().unwrap();
    let normal = tauri::image::Image::new_owned(icon.rgba().to_vec(), icon.width(), icon.height());
    let blank = tauri::image::Image::new_owned(vec![0; normal.rgba().len()], normal.width(), normal.height());
    std::thread::spawn(move || {
        let mut hidden = false;
        loop {
            std::thread::sleep(std::time::Duration::from_millis(600));
            let next = TRAY_UNREAD.load(Ordering::Relaxed) && TRAY_FLASH_ENABLED.load(Ordering::Relaxed) && !hidden;
            if next != hidden {
                if let Some(tray) = app.tray_by_id("workspace") {
                    let _ = tray.set_icon(Some(if next { blank.clone() } else { normal.clone() }));
                }
                hidden = next;
            }
        }
    });
}

// A minimized Windows window reports a transient viewport (often 0 × 0).
// Keep the child WebView's last usable size so responsive content does not
// switch to its mobile layout and animate back when the window is restored.
fn workspace_size(size: LogicalSize<f64>, minimized: bool) -> Option<LogicalSize<f64>> {
    if minimized || size.width <= 0.0 || size.height <= HEADER {
        return None;
    }
    Some(LogicalSize::new(size.width, size.height - HEADER))
}

// Tauri's visible macOS titlebar uses a full-size content view. Its bounds
// include the system titlebar, while the local shell is laid out below it.
// Size the child from AppKit's unobscured content rect, in parent coordinates.
#[cfg(target_os = "macos")]
fn macos_workspace_frame(
    bounds: objc2_foundation::NSRect,
    flipped: bool,
    minimized: bool,
) -> Option<objc2_foundation::NSRect> {
    use objc2_foundation::{NSPoint, NSRect, NSSize};
    let size = workspace_size(LogicalSize::new(bounds.size.width, bounds.size.height), minimized)?;
    Some(NSRect::new(
        NSPoint::new(bounds.origin.x, bounds.origin.y + if flipped { HEADER } else { 0.0 }),
        NSSize::new(size.width, size.height),
    ))
}

#[cfg(target_os = "macos")]
fn resize_macos_workspace(view: &Webview) -> tauri::Result<()> {
    view.with_webview(|platform| {
        use objc2_app_kit::NSView;
        // The platform callback runs on the main thread, and Tauri owns the
        // WKWebView (an NSView subclass) for the duration of this callback.
        let view = unsafe { &*platform.inner().cast::<NSView>() };
        let (Some(parent), Some(window)) = (unsafe { view.superview() }, view.window()) else {
            return;
        };
        let content = parent.convertRect_fromView(window.contentLayoutRect(), None);
        if let Some(frame) = macos_workspace_frame(content, parent.isFlipped(), window.isMiniaturized()) {
            view.setFrame(frame);
        }
    })
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

fn desktop_signal_url(title: &str, origin: &tauri::Url) -> Option<tauri::Url> {
    if title.len() > MAX_DESKTOP_SIGNAL_BYTES {
        return None;
    }
    let signal = title.strip_prefix(DESKTOP_SIGNAL_PREFIX)?;
    let url = origin.join(signal).ok()?;
    if url.origin() != origin.origin()
        || url.fragment().is_some()
        || !matches!(url.path(), "/__desktop_toolbar__" | "/__desktop_shortcuts__")
    {
        return None;
    }
    Some(url)
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

// macOS window coordinates are points. Restoring another display's saved physical
// pixels through the startup display's scale can halve both the frame and position.
// Let AppKit preserve its own screen-aware frame, scoped to this app and window.
#[cfg(target_os = "macos")]
fn restore_macos_frame(window: tauri::Window) {
    use objc2::MainThreadMarker;
    use objc2_app_kit::{NSScreen, NSWindow};
    use objc2_foundation::{NSPoint, NSString, NSUserDefaults};
    use tauri_plugin_window_state::WindowExt;

    let name = format!("{}.{}", window.app_handle().config().identifier, window.label());
    let pet = window.label() == "pet";
    // Tao queues its initial position on this queue. Restore after that operation,
    // and only then show the window, or the queued default position wins later.
    dispatch2::DispatchQueue::main().exec_async(move || {
        if let Ok(pointer) = window.ns_window() {
            // Tauri owns this NSWindow for the lifetime of the cloned handle.
            // AppKit is accessed only on the macOS main thread.
            let window = unsafe { &*pointer.cast::<NSWindow>() };
            let default_frame = window.frame();
            let name = NSString::from_str(&name);
            let key = NSString::from_str(&format!("NSWindow Frame {name}"));
            let saved = NSUserDefaults::standardUserDefaults().stringForKey(&key);
            let areas = NSScreen::screens(MainThreadMarker::new().expect("main dispatch queue"))
                .iter().map(|screen| screen.visibleFrame()).collect::<Vec<_>>();
            let frame = saved.and_then(|saved| saved_macos_frame(&saved.to_string(), &areas));
            // Setting the autosave name can itself relocate the frame to the
            // currently active display. Apply our validated global points after it.
            window.setFrameAutosaveName(&name);
            if let Some(frame) = frame {
                window.setFrame_display(frame, false);
            } else {
                window.setFrame_display(default_frame, false);
                if pet {
                    if let Some(screen) = window.screen() {
                        let area = screen.visibleFrame();
                        window.setFrameOrigin(NSPoint::new(
                            area.origin.x + area.size.width - 204.0,
                            area.origin.y + 214.0 - window.frame().size.height,
                        ));
                    }
                }
            }
            window.saveFrameUsingName(&name);
        }
        // Preserve the plugin's fullscreen/maximized state after restoring the
        // normal AppKit frame; these flags deliberately exclude position/size.
        let _ = window.restore_state(window_state_flags());
        let _ = window.show();
        if window.label() == "main" {
            let _ = window.set_focus();
        }
    });
}

#[cfg(target_os = "macos")]
fn saved_macos_frame(value: &str, areas: &[objc2_foundation::NSRect]) -> Option<objc2_foundation::NSRect> {
    use objc2_foundation::{NSPoint, NSRect, NSSize};
    // AppKit's frame descriptor stores window x/y/width/height, then screen frame.
    let values = value.split_whitespace().map(str::parse::<f64>)
        .collect::<Result<Vec<_>, _>>().ok()?;
    if values.len() != 8 || values.iter().any(|value| !value.is_finite())
        || values[2] <= 0.0 || values[3] <= 0.0 {
        return None;
    }
    let frame = NSRect::new(NSPoint::new(values[0], values[1]), NSSize::new(values[2], values[3]));
    let overlap = |area: &&NSRect| {
        let width = (frame.origin.x + frame.size.width).min(area.origin.x + area.size.width)
            - frame.origin.x.max(area.origin.x);
        let height = (frame.origin.y + frame.size.height).min(area.origin.y + area.size.height)
            - frame.origin.y.max(area.origin.y);
        width.max(0.0) * height.max(0.0)
    };
    let area = areas.iter().filter(|area| overlap(area) > 0.0)
        .max_by(|a, b| overlap(a).total_cmp(&overlap(b)))?;
    let width = frame.size.width.min(area.size.width);
    let height = frame.size.height.min(area.size.height);
    Some(NSRect::new(
        NSPoint::new(
            frame.origin.x.clamp(area.origin.x, area.origin.x + area.size.width - width),
            frame.origin.y.clamp(area.origin.y, area.origin.y + area.size.height - height),
        ),
        NSSize::new(width, height),
    ))
}

fn window_state_flags() -> tauri_plugin_window_state::StateFlags {
    use tauri_plugin_window_state::StateFlags;
    let flags = StateFlags::all() & !StateFlags::VISIBLE;
    #[cfg(target_os = "macos")]
    let flags = flags & !(StateFlags::POSITION | StateFlags::SIZE);
    flags
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
    let close = MenuItemBuilder::with_id("mac-close", "关闭当前页签")
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
            if let Some(workspace) = app.get_webview("workspace") {
                let _ = workspace.eval("window.dispatchEvent(new Event('wao-close-active-tab'))");
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
        "compact"
            | "bubble"
            | "chat"
            | "hide"
            | "workspace"
            | "toggle-workspace"
            | "drag"
            | "open-chat"
    ) {
        Some(action.to_string())
    } else {
        None
    }
}

// Values crossing from the remote page are bounded display data only.
fn toolbar_state(url: &tauri::Url) -> serde_json::Value {
    let mut state = serde_json::json!({"ready": false, "chat": "0", "notifications": "0", "theme": "light", "context": "", "contextItems": [], "menus": []});
    for (key, value) in url.query_pairs() {
        match key.as_ref() {
            "theme" if value == "light" || value == "dark" => {
                state["theme"] = serde_json::json!(value)
            }
            "ready" => state["ready"] = serde_json::json!(value == "1"),
            "chat" | "notifications"
                if value == "99+" || value.parse::<u8>().is_ok_and(|n| n <= 99) =>
            {
                state[key.as_ref()] = serde_json::json!(value);
            }
            "context" if value.chars().count() <= 80 && !value.chars().any(char::is_control) => {
                state["context"] = serde_json::json!(value);
            }
            "contextItems" | "menus" => {
                if let Ok(labels) = serde_json::from_str::<Vec<String>>(&value) {
                    if labels.len() <= 50
                        && labels.iter().all(|label| {
                            label.chars().count() <= 80 && !label.chars().any(char::is_control)
                        })
                    {
                        state[key.as_ref()] = serde_json::json!(labels);
                    }
                }
            }
            _ => {}
        }
    }
    state
}

fn valid_shortcut(value: &str) -> bool {
    let parts = value.split('+').collect::<Vec<_>>();
    if !(2..=4).contains(&parts.len())
        || !parts
            .last()
            .is_some_and(|key| key.len() == 1 && key.as_bytes()[0].is_ascii_alphanumeric())
    {
        return false;
    }
    let mut seen = std::collections::HashSet::new();
    parts[..parts.len() - 1]
        .iter()
        .all(|part| matches!(*part, "CommandOrControl" | "Alt" | "Shift") && seen.insert(*part))
}

// Remote workspace can only request bounded combinations for three fixed product actions.
fn shortcut_config(url: &tauri::Url) -> serde_json::Value {
    let mut config = serde_json::Map::new();
    for (key, value) in url.query_pairs() {
        if matches!(key.as_ref(), "new-task" | "new-note" | "chat") && valid_shortcut(&value) {
            config.insert(key.into_owned(), serde_json::json!(value));
        }
    }
    serde_json::Value::Object(config)
}

#[cfg(target_os = "macos")]
fn pet_frame(frame: objc2_foundation::NSRect, area: objc2_foundation::NSRect, width: f64, height: f64) -> objc2_foundation::NSRect {
    use objc2_foundation::{NSPoint, NSRect, NSSize};
    let width = width.min(area.size.width);
    let height = height.min(area.size.height);
    let x = (frame.origin.x + frame.size.width - width)
        .clamp(area.origin.x, area.origin.x + area.size.width - width);
    let y = frame.origin.y
        .clamp(area.origin.y, area.origin.y + area.size.height - height);
    NSRect::new(NSPoint::new(x, y), NSSize::new(width, height))
}

fn resize_pet(window: &tauri::WebviewWindow, width: f64, height: f64) {
    #[cfg(target_os = "macos")]
    {
        use objc2_app_kit::NSWindow;
        let window = window.clone();
        // Read frame and screen together in AppKit points. Separate physical
        // getters can straddle a backing-scale change during cross-screen restore.
        dispatch2::DispatchQueue::main().exec_async(move || {
            if let Ok(pointer) = window.ns_window() {
                // The owned Tauri handle keeps the NSWindow alive on this queue.
                let window = unsafe { &*pointer.cast::<NSWindow>() };
                if let Some(screen) = window.screen() {
                    window.setFrame_display(pet_frame(window.frame(), screen.visibleFrame(), width, height), true);
                }
            }
        });
    }
    #[cfg(not(target_os = "macos"))]
    {
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
        .visible(!cfg!(target_os = "macos"))
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
    #[cfg(not(target_os = "macos"))]
    if let Ok(Some(monitor)) = window.current_monitor() {
        let area = monitor.work_area();
        let scale = monitor.scale_factor();
        window.set_position(tauri::PhysicalPosition::new(
            area.position.x + area.size.width as i32 - (204.0 * scale) as i32,
            area.position.y + area.size.height as i32 - (214.0 * scale) as i32,
        ))?;
    }
    #[cfg(target_os = "macos")]
    restore_macos_frame(window.as_ref().window());
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
    let scale = window.scale_factor().map_err(|e| e.to_string())?;
    let bounds = webview.bounds().map_err(|e| e.to_string())?;
    let size = bounds.size.to_logical::<f64>(scale);
    #[cfg(target_os = "macos")]
    let position = {
        // The shell already excludes the system titlebar. Match its origin
        // before adding our toolbar so the first child frame is correct too.
        let shell = bounds.position.to_logical::<f64>(scale);
        LogicalPosition::new(shell.x, shell.y + HEADER)
    };
    #[cfg(not(target_os = "macos"))]
    let position = LogicalPosition::new(0.0, HEADER);
    let origin = workspace_url();
    let navigation_origin = origin.clone();
    let navigation_app = app.clone();
    let title_origin = origin.clone();
    let popup_app = app.clone();
    let builder = WebviewBuilder::new("workspace", WebviewUrl::External(origin))
        .disable_drag_drop_handler()
        .initialization_script("Object.defineProperty(window, '__WAO_DESKTOP__', { value: true }); Object.defineProperty(window, '__WAO_DESKTOP_TOOLBAR__', { value: true }); Object.defineProperty(window, '__WAO_DESKTOP_BRIDGE__', { value: 2 });")
        .on_navigation(move |url| {
            if url.origin() == navigation_origin.origin() && url.path() == "/__desktop_toolbar__" {
                if navigation_app.get_webview("workspace").and_then(|view| view.url().ok())
                    .is_some_and(|current| current.origin() == navigation_origin.origin()) {
                    let state = toolbar_state(url);
                    TRAY_UNREAD.store(has_unread(&state), Ordering::Relaxed);
                    let _ = navigation_app.emit_to("main", "toolbar-state", state);
                }
                return false;
            }
            if url.origin() == navigation_origin.origin() && url.path() == "/__desktop_shortcuts__" {
                if navigation_app.get_webview("workspace").and_then(|view| view.url().ok())
                    .is_some_and(|current| current.origin() == navigation_origin.origin()) {
                    let _ = navigation_app.emit_to("main", "shortcut-config", shortcut_config(url));
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
        .on_document_title_changed(move |view, title| {
            if !view
                .url()
                .ok()
                .is_some_and(|current| current.origin() == title_origin.origin())
            {
                return;
            }
            let Some(signal) = desktop_signal_url(&title, &title_origin) else {
                return;
            };
            match signal.path() {
                "/__desktop_toolbar__" => {
                    let state = toolbar_state(&signal);
                    TRAY_UNREAD.store(has_unread(&state), Ordering::Relaxed);
                    let _ = view.app_handle().emit_to("main", "toolbar-state", state);
                }
                "/__desktop_shortcuts__" => {
                    let _ = view
                        .app_handle()
                        .emit_to("main", "shortcut-config", shortcut_config(&signal));
                }
                _ => {}
            }
        })
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
                TRAY_UNREAD.store(false, Ordering::Relaxed);
                let _ = view.app_handle().emit_to("main", "toolbar-state", serde_json::json!({"ready": false, "chat": "0", "notifications": "0", "theme": "light", "context": "", "contextItems": [], "menus": []}));
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
    let workspace = window
        .add_child(
            builder,
            position,
            LogicalSize::new(size.width, (size.height - HEADER).max(1.0)),
        )
        .map_err(|e| e.to_string())?;
    #[cfg(target_os = "macos")]
    resize_macos_workspace(&workspace).map_err(|e| e.to_string())?;
    #[cfg(not(target_os = "macos"))]
    let _ = workspace;
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
        }
        "new-task" | "new-note" | "theme" | "shortcuts" | "chat" | "notifications" | "fold" => {
            if workspace.url().map_err(|e| e.to_string())?.origin() != workspace_url().origin() {
                return Err("当前页面不支持工作区快捷操作".into());
            }
            // Only the matched fixed selector reaches eval; no caller-supplied code is accepted.
            workspace.eval(format!(
                "document.querySelector('[data-desktop-action=\"{}\"]')?.click()",
                action
            ))
        }
        action
            if action
                .strip_prefix("menu/")
                .is_some_and(|index| index.parse::<u8>().is_ok_and(|value| value < 50)) =>
        {
            if workspace.url().map_err(|e| e.to_string())?.origin() != workspace_url().origin() {
                return Err("当前页面不支持工作区快捷操作".into());
            }
            let index = action.strip_prefix("menu/").unwrap();
            workspace.eval(format!(
                "document.querySelector('[data-desktop-menu-index=\"{}\"]')?.click()",
                index
            ))
        }
        action
            if action
                .strip_prefix("context/")
                .is_some_and(|index| index.parse::<u8>().is_ok_and(|value| value < 50)) =>
        {
            if workspace.url().map_err(|e| e.to_string())?.origin() != workspace_url().origin() {
                return Err("当前页面不支持工作区快捷操作".into());
            }
            let index = action.strip_prefix("context/").unwrap();
            workspace.eval(format!(
                "const s=document.querySelector('.context-nav select');const o=s?.querySelectorAll('option:not(:disabled)')[{}];if(s&&o){{s.value=o.value;s.dispatchEvent(new Event('change',{{bubbles:true}}))}}",
                index
            ))
        }
        "back" => workspace.eval("history.back()"),
        "forward" => workspace.eval("history.forward()"),
        "reload" => workspace.eval("window.dispatchEvent(new Event('wao-workspace-reload'))"),
        "hide" => workspace.hide(),
        "show" => workspace.show(),
        "reveal" => {
            show(&app);
            workspace.show()
        }
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
        .plugin(tauri_plugin_global_shortcut::Builder::new().build())
        .plugin(tauri_plugin_updater::Builder::new().build())
        .plugin(
            tauri_plugin_window_state::Builder::default()
                .with_state_flags(window_state_flags())
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
                #[cfg(target_os = "macos")]
                WindowEvent::Resized(_) | WindowEvent::ScaleFactorChanged { .. } => {
                    if let Some(view) = window.app_handle().get_webview("workspace") {
                        let _ = resize_macos_workspace(&view);
                    }
                }
                #[cfg(not(target_os = "macos"))]
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
            #[cfg(target_os = "macos")]
            if let Some(window) = app.get_window("main") {
                restore_macos_frame(window);
            }
            #[cfg(not(target_os = "macos"))]
            show(app.handle());
            #[cfg(target_os = "macos")]
            macos_menu(app)?;
            let open = MenuItemBuilder::with_id("open", "打开 WAO 工作区").build(app)?;
            let pet = MenuItemBuilder::with_id("pet", "显示/隐藏宠物").build(app)?;
            let update = MenuItemBuilder::with_id("update", "检查客户端更新").build(app)?;
            let quit = MenuItemBuilder::with_id("quit", "退出 WAO…").build(app)?;
            let enabled = tray_preference_path(app.handle())
                .and_then(|path| std::fs::read_to_string(path).ok())
                .and_then(|value| serde_json::from_str::<bool>(&value).ok()).unwrap_or(true);
            TRAY_FLASH_ENABLED.store(enabled, Ordering::Relaxed);
            let flash = CheckMenuItemBuilder::with_id("tray-flash", "新消息闪烁")
                .checked(enabled).enabled(cfg!(target_os = "windows")).build(app)?;
            let menu = MenuBuilder::new(app)
                .items(&[&open, &pet, &update, &flash])
                .separator()
                .item(&quit)
                .build()?;
            TrayIconBuilder::with_id("workspace")
                .icon(app.default_window_icon().unwrap().clone())
                .tooltip("WAO · 你的桌面工作区")
                .menu(&menu)
                .show_menu_on_left_click(cfg!(target_os = "macos"))
                .on_menu_event(move |app, event| {
                    match event.id.as_ref() {
                        "tray-flash" => {
                            let old = TRAY_FLASH_ENABLED.load(Ordering::Relaxed);
                            let saved = tray_preference_path(app).is_some_and(|path| {
                                path.parent().is_some_and(|dir| std::fs::create_dir_all(dir).is_ok())
                                    && std::fs::write(path, if old { "false" } else { "true" }).is_ok()
                            });
                            if saved { TRAY_FLASH_ENABLED.store(!old, Ordering::Relaxed); }
                            let _ = flash.set_checked(if saved { !old } else { old });
                            let _ = flash.set_text(if saved { "新消息闪烁" } else { "新消息闪烁（保存失败，点击重试）" });
                        }
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
            #[cfg(target_os = "windows")]
            start_tray_flash(app.handle().clone());
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
    #[cfg(target_os = "macos")]
    #[test]
    fn saved_frames_keep_their_display_regardless_of_the_active_screen_order() {
        use objc2_foundation::{NSPoint, NSRect, NSSize};
        let primary = NSRect::new(NSPoint::new(0.0, 60.0), NSSize::new(1792.0, 1035.0));
        let secondary = NSRect::new(NSPoint::new(1792.0, 0.0), NSSize::new(1920.0, 1055.0));
        let saved = "206 267 1100 760 0 0 1792 1095";
        let expected = NSRect::new(NSPoint::new(206.0, 267.0), NSSize::new(1100.0, 760.0));
        assert_eq!(saved_macos_frame(saved, &[secondary, primary]), Some(expected));
        assert_eq!(saved_macos_frame(saved, &[primary, secondary]), Some(expected));
        let saved = "2300 220 1000 700 1792 0 1920 1055";
        assert_eq!(saved_macos_frame(saved, &[primary, secondary]).unwrap().origin, NSPoint::new(2300.0, 220.0));
        assert_eq!(saved_macos_frame(saved, &[primary]), None);
        assert_eq!(saved_macos_frame("NaN 0 100 100 0 0 100 100", &[primary]), None);
        assert_eq!(saved_macos_frame("0 0 -1 100 0 0 100 100", &[primary]), None);
    }

    #[cfg(target_os = "macos")]
    #[test]
    fn pet_expansion_and_compaction_preserve_secondary_screen_anchor() {
        use objc2_foundation::{NSPoint, NSRect, NSSize};
        let area = NSRect::new(NSPoint::new(1792.0, 0.0), NSSize::new(1920.0, 1055.0));
        let compact = NSRect::new(NSPoint::new(2066.0, 212.0), NSSize::new(120.0, 120.0));
        let bubble = pet_frame(compact, area, 260.0, 190.0);
        assert_eq!(bubble.origin, NSPoint::new(1926.0, 212.0));
        assert_eq!(pet_frame(bubble, area, 120.0, 120.0), compact);
        // An unchanged bubble on the 1x display must not jump after 2x startup.
        assert_eq!(pet_frame(bubble, area, 260.0, 190.0), bubble);
    }

    #[cfg(target_os = "macos")]
    #[test]
    fn pet_frame_stays_inside_work_area_even_when_the_saved_screen_is_gone() {
        use objc2_foundation::{NSPoint, NSRect, NSSize};
        let frame = NSRect::new(NSPoint::new(3452.0, -30.0), NSSize::new(260.0, 190.0));
        let area = NSRect::new(NSPoint::new(0.0, 60.0), NSSize::new(1792.0, 1035.0));
        assert_eq!(pet_frame(frame, area, 260.0, 190.0).origin, NSPoint::new(1532.0, 60.0));
        let tiny = NSRect::new(NSPoint::new(-100.0, 10.0), NSSize::new(100.0, 80.0));
        assert_eq!(pet_frame(frame, tiny, 260.0, 190.0), tiny);
    }

    #[test]
    fn tray_unread_requires_ready_and_bounded_positive_counts() {
        assert!(!has_unread(&serde_json::json!({"ready":false,"chat":"1"})));
        assert!(!has_unread(&serde_json::json!({"ready":true,"chat":"0","notifications":"0"})));
        assert!(has_unread(&serde_json::json!({"ready":true,"chat":"99+"})));
        assert!(has_unread(&serde_json::json!({"ready":true,"notifications":"3"})));
        assert!(!has_unread(&serde_json::json!({"ready":true,"chat":"999"})));
    }

    #[cfg(target_os = "macos")]
    #[test]
    fn macos_workspace_stays_below_toolbar_in_current_content_bounds() {
        use objc2_foundation::{NSPoint, NSRect, NSSize};
        // Normal, minimum, fullscreen and restored content sizes; the outer
        // window/titlebar dimensions are deliberately absent from the input.
        for (width, height) in [(1100.0, 760.0), (800.0, 560.0), (1920.0, 1080.0), (1100.0, 732.0)] {
            for flipped in [false, true] {
                let bounds = NSRect::new(NSPoint::new(7.0, 11.0), NSSize::new(width, height));
                let frame = macos_workspace_frame(bounds, flipped, false).unwrap();
                let top = if flipped { frame.origin.y - bounds.origin.y }
                    else { bounds.origin.y + height - frame.origin.y - frame.size.height };
                assert_eq!(top, HEADER);
                assert_eq!(frame.size, NSSize::new(width, height - HEADER));
                assert_eq!(frame.origin.x, bounds.origin.x);
                assert!(macos_workspace_frame(bounds, flipped, true).is_none());
            }
        }
        for (width, height) in [(0.0, 760.0), (1100.0, 0.0), (1100.0, HEADER)] {
            assert!(macos_workspace_frame(NSRect::new(NSPoint::new(0.0, 0.0), NSSize::new(width, height)), false, false).is_none());
        }
    }

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
        let url = tauri::Url::parse("https://wao.wayuwa.cn/__desktop_toolbar__?ready=1&chat=3&notifications=99%2B&context=%E9%A6%96%E9%A1%B5&contextItems=%5B%22%E4%BB%8A%E6%97%A5%22%5D&menus=%5B%22%E6%9B%B4%E6%96%B0%E6%97%A5%E5%BF%97%22%5D&script=ignored").unwrap();
        assert_eq!(
            toolbar_state(&url),
            serde_json::json!({"ready": true, "chat": "3", "notifications": "99+", "theme": "light", "context": "首页", "contextItems": ["今日"], "menus": ["更新日志"]})
        );
        let url = tauri::Url::parse(
            "https://wao.wayuwa.cn/__desktop_toolbar__?ready=wrong&chat=-1&notifications=1000",
        )
        .unwrap();
        assert_eq!(
            toolbar_state(&url),
            serde_json::json!({"ready": false, "chat": "0", "notifications": "0", "theme": "light", "context": "", "contextItems": [], "menus": []})
        );
        let dark =
            tauri::Url::parse("https://wao.wayuwa.cn/__desktop_toolbar__?theme=dark").unwrap();
        assert_eq!(toolbar_state(&dark)["theme"], "dark");
        let invalid =
            tauri::Url::parse("https://wao.wayuwa.cn/__desktop_toolbar__?theme=arbitrary").unwrap();
        assert_eq!(toolbar_state(&invalid)["theme"], "light");
        let origin = tauri::Url::parse(PRODUCTION).unwrap();
        assert_eq!(
            pet_action_name(
                &origin.join("/__desktop_pet__/toggle-workspace").unwrap(),
                &origin
            )
            .as_deref(),
            Some("toggle-workspace")
        );
        assert!(pet_action_name(
            &tauri::Url::parse("https://evil.test/__desktop_pet__/toggle-workspace").unwrap(),
            &origin
        )
        .is_none());
    }

    #[test]
    fn desktop_title_signal_only_accepts_bounded_same_origin_bridge_paths() {
        let origin = tauri::Url::parse(PRODUCTION).unwrap();
        let toolbar = desktop_signal_url(
            "WAO_DESKTOP_SIGNAL:/__desktop_toolbar__?ready=1&chat=3",
            &origin,
        )
        .unwrap();
        assert_eq!(toolbar.path(), "/__desktop_toolbar__");
        assert_eq!(toolbar_state(&toolbar)["chat"], "3");
        let shortcuts = desktop_signal_url(
            "WAO_DESKTOP_SIGNAL:/__desktop_shortcuts__?new-note=Alt%2BShift%2BY",
            &origin,
        )
        .unwrap();
        assert_eq!(shortcut_config(&shortcuts)["new-note"], "Alt+Shift+Y");
        for title in [
            "/__desktop_toolbar__?ready=1",
            "WAO_DESKTOP_SIGNAL:https://evil.test/__desktop_toolbar__?ready=1",
            "WAO_DESKTOP_SIGNAL:/api/tasks",
            "WAO_DESKTOP_SIGNAL:/__desktop_toolbar__#ignored",
        ] {
            assert!(desktop_signal_url(title, &origin).is_none());
        }
        assert!(desktop_signal_url(
            &format!("{DESKTOP_SIGNAL_PREFIX}/__desktop_toolbar__?x={}", "a".repeat(MAX_DESKTOP_SIGNAL_BYTES)),
            &origin,
        )
        .is_none());
    }

    #[test]
    fn global_shortcut_bridge_only_accepts_fixed_actions_and_bounded_combinations() {
        let url = tauri::Url::parse("https://wao.wayuwa.cn/__desktop_shortcuts__?new-note=Alt%2BShift%2BY&chat=CommandOrControl%2BAlt%2BF&unknown=Alt%2BX&new-task=Q").unwrap();
        assert_eq!(
            shortcut_config(&url),
            serde_json::json!({
                "new-note": "Alt+Shift+Y",
                "chat": "CommandOrControl+Alt+F"
            })
        );
        assert!(valid_shortcut("Alt+Q"));
        assert!(!valid_shortcut("Alt+Alt+Q"));
        assert!(!valid_shortcut("Alt+F12"));
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
