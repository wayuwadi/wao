use tauri::{
    menu::{MenuBuilder, MenuItemBuilder},
    tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent},
    Emitter, Manager,
};
use tauri_plugin_window_state::StateFlags;

fn show_pet(app: &tauri::AppHandle) {
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.show();
        let _ = window.set_focus();
    }
}

fn toggle_pet(app: &tauri::AppHandle) {
    if let Some(window) = app.get_webview_window("main") {
        if window.is_visible().unwrap_or(false) {
            let _ = window.hide();
        } else {
            show_pet(app);
        }
    }
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_process::init())
        .plugin(tauri_plugin_updater::Builder::new().build())
        .plugin(
            tauri_plugin_window_state::Builder::default()
                .with_state_flags(StateFlags::POSITION)
                .build(),
        )
        .setup(|app| {
            #[cfg(target_os = "macos")]
            app.set_activation_policy(tauri::ActivationPolicy::Accessory);

            let open = MenuItemBuilder::with_id("open", "打开 WAO").build(app)?;
            let show = MenuItemBuilder::with_id("show", "显示桌面宠物").build(app)?;
            let update = MenuItemBuilder::with_id("update", "检查更新").build(app)?;
            let quit = MenuItemBuilder::with_id("quit", "退出").build(app)?;
            let menu = MenuBuilder::new(app)
                .items(&[&open, &show, &update])
                .separator()
                .item(&quit)
                .build()?;

            let icon = app
                .default_window_icon()
                .cloned()
                .expect("desktop icon must exist");
            TrayIconBuilder::new()
                .icon(icon)
                .tooltip("WAO 桌面宠物")
                .menu(&menu)
                .show_menu_on_left_click(false)
                .on_menu_event(|app, event| match event.id.as_ref() {
                    "open" => {
                        let _ = app.emit("desktop://open-wao", ());
                    }
                    "show" => show_pet(app),
                    "update" => {
                        show_pet(app);
                        let _ = app.emit("desktop://check-for-updates", ());
                    }
                    "quit" => app.exit(0),
                    _ => {}
                })
                .on_tray_icon_event(|tray, event| {
                    if let TrayIconEvent::Click {
                        button: MouseButton::Left,
                        button_state: MouseButtonState::Up,
                        ..
                    } = event
                    {
                        toggle_pet(tray.app_handle());
                    }
                })
                .build(app)?;

            if let Some(window) = app.get_webview_window("main") {
                // The window-state plugin restores a saved position after setup. This is only
                // the stable first-run fallback near the current work area's lower-right edge.
                if let Ok(Some(monitor)) = window.current_monitor() {
                    let area = monitor.work_area();
                    let scale = monitor.scale_factor();
                    let size = window.outer_size().unwrap_or_default();
                    let x = area.position.x + area.size.width as i32
                        - size.width as i32
                        - (24.0 * scale) as i32;
                    let y = area.position.y + area.size.height as i32
                        - size.height as i32
                        - (20.0 * scale) as i32;
                    let _ = window.set_position(tauri::PhysicalPosition::new(x, y));
                }
            }
            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("error while running WAO desktop pet");
}
