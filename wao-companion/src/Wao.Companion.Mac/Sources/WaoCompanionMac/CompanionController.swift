import AppKit
import ServiceManagement
import SwiftUI

@MainActor
final class CompanionController: NSObject, NSWindowDelegate {
    let store = CompanionStore()
    private let preview = CommandLine.arguments.contains("--preview")
    private var petPanel: PetPanel!
    private var activityPanel: NSPanel!
    private var pairingWindow: NSWindow!
    private var statusItem: NSStatusItem!
    private var roamTimer: Timer?
    private var roamAfter = Date().addingTimeInterval(10)
    private var roamSteps = 0
    private var roamRight = true

    func start() {
        NSApp.setActivationPolicy(.accessory)
        createPetPanel()
        createActivityPanel()
        createPairingWindow()
        createStatusItem()
        restorePetPosition()
        petPanel.orderFrontRegardless()
        startRoaming()
        NotificationCenter.default.addObserver(forName: .companionPaired, object: nil, queue: .main) { [weak self] _ in
            self?.pairingWindow.orderOut(nil)
            self?.petPanel.orderFrontRegardless()
        }
        NotificationCenter.default.addObserver(forName: .companionNeedsPairing, object: nil, queue: .main) { [weak self] _ in
            self?.showPairing()
        }
        store.start(preview: preview)
        if !preview && SecureTokenStore.load() == nil { showPairing() }
    }

    private func createPetPanel() {
        petPanel = PetPanel(contentRect: NSRect(x: 0, y: 0, width: 205, height: 235),
                            styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: false)
        petPanel.isOpaque = false
        petPanel.backgroundColor = .clear
        petPanel.hasShadow = false
        petPanel.level = .floating
        petPanel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
        petPanel.hidesOnDeactivate = false
        petPanel.contentView = NSHostingView(rootView: PetView(store: store))
        petPanel.onClick = { [weak self] in self?.toggleActivities() }
        petPanel.onDragging = { [weak self] dragging in self?.store.setDragging(dragging) }
        petPanel.onMoved = { [weak self] in
            guard let self else { return }
            self.store.playLanding()
            UserDefaults.standard.set(self.petPanel.frame.origin.x, forKey: "petLeft")
            UserDefaults.standard.set(self.petPanel.frame.origin.y, forKey: "petBottom")
            self.roamAfter = Date().addingTimeInterval(10)
        }
    }

    private func createActivityPanel() {
        activityPanel = NSPanel(contentRect: NSRect(x: 0, y: 0, width: 380, height: 440),
                                styleMask: [.titled, .fullSizeContentView, .nonactivatingPanel],
                                backing: .buffered, defer: false)
        activityPanel.titleVisibility = .hidden
        activityPanel.titlebarAppearsTransparent = true
        activityPanel.level = .floating
        activityPanel.isReleasedWhenClosed = false
        activityPanel.contentView = NSHostingView(rootView: ActivityView(store: store) { [weak self] in
            self?.activityPanel.orderOut(nil)
        })
    }

    private func createPairingWindow() {
        pairingWindow = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 520, height: 390),
                                 styleMask: [.titled, .closable], backing: .buffered, defer: false)
        pairingWindow.title = "连接 WAO 桌面宠物"
        pairingWindow.isReleasedWhenClosed = false
        pairingWindow.center()
        pairingWindow.contentView = NSHostingView(rootView: PairingView(store: store))
    }

    private func createStatusItem() {
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.squareLength)
        statusItem.button?.image = NSImage(systemSymbolName: "flame.fill", accessibilityDescription: "WAO Companion")
        let menu = NSMenu()
        menu.addItem(withTitle: "显示桌宠", action: #selector(showPet), keyEquivalent: "")
        menu.addItem(withTitle: "隐藏桌宠", action: #selector(hidePet), keyEquivalent: "")
        menu.addItem(withTitle: "打开 WAO", action: #selector(openWao), keyEquivalent: "")
        menu.addItem(withTitle: "立即刷新", action: #selector(refresh), keyEquivalent: "")
        menu.addItem(.separator())
        let login = NSMenuItem(title: "登录时启动", action: #selector(toggleLoginItem(_:)), keyEquivalent: "")
        login.state = SMAppService.mainApp.status == .enabled ? .on : .off
        menu.addItem(login)
        menu.addItem(withTitle: "重新配对", action: #selector(rePair), keyEquivalent: "")
        menu.addItem(.separator())
        menu.addItem(withTitle: "退出 WAO Companion", action: #selector(quit), keyEquivalent: "q")
        for item in menu.items { item.target = self }
        statusItem.menu = menu
    }

    private func restorePetPosition() {
        let visible = NSScreen.main?.visibleFrame ?? NSRect(x: 0, y: 0, width: 1440, height: 900)
        let left = UserDefaults.standard.object(forKey: "petLeft") as? Double
        let bottom = UserDefaults.standard.object(forKey: "petBottom") as? Double
        let origin = NSPoint(x: left ?? visible.maxX - petPanel.frame.width - 18,
                             y: bottom ?? visible.minY + 18)
        petPanel.setFrameOrigin(clamp(origin, to: visible))
    }

    private func toggleActivities() {
        if activityPanel.isVisible { activityPanel.orderOut(nil); roamAfter = Date().addingTimeInterval(10); return }
        guard let screen = petPanel.screen else { return }
        let work = screen.visibleFrame
        let left = petPanel.frame.minX - activityPanel.frame.width - 10
        let x = left >= work.minX ? left : min(work.maxX - activityPanel.frame.width, petPanel.frame.maxX + 10)
        let y = min(max(petPanel.frame.maxY - activityPanel.frame.height, work.minY), work.maxY - activityPanel.frame.height)
        activityPanel.setFrameOrigin(NSPoint(x: x, y: y))
        activityPanel.orderFrontRegardless()
    }

    private func startRoaming() {
        roamTimer = Timer.scheduledTimer(withTimeInterval: 0.22, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.advanceRoaming() }
        }
    }

    private func advanceRoaming() {
        guard !activityPanel.isVisible, !store.isDragging, !store.isHidden,
              !NSWorkspace.shared.accessibilityDisplayShouldReduceMotion,
              ["IDLE", "READY"].contains(store.snapshot.status), Date() >= roamAfter,
              let screen = petPanel.screen else { return }
        let work = screen.visibleFrame
        if roamSteps == 0 {
            if petPanel.frame.minX <= work.minX + 8 { roamRight = true }
            else if petPanel.frame.maxX >= work.maxX - 8 { roamRight = false }
            else { roamRight = Bool.random() }
            roamSteps = Int.random(in: 16...28)
        }
        var point = petPanel.frame.origin
        point.x += roamRight ? 0.9 : -0.9
        petPanel.setFrameOrigin(clamp(point, to: work))
        roamSteps -= 1
        if roamSteps == 0 { roamAfter = Date().addingTimeInterval(Double.random(in: 10...18)) }
    }

    private func clamp(_ point: NSPoint, to work: NSRect) -> NSPoint {
        NSPoint(x: min(max(point.x, work.minX), work.maxX - petPanel.frame.width),
                y: min(max(point.y, work.minY), work.maxY - petPanel.frame.height))
    }

    private func showPairing() {
        pairingWindow.center()
        pairingWindow.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
    }

    @objc private func showPet() { store.isHidden = false; petPanel.orderFrontRegardless() }
    @objc private func hidePet() { store.isHidden = true; activityPanel.orderOut(nil); petPanel.orderOut(nil) }
    @objc private func openWao() { NSWorkspace.shared.open(URL(string: "https://wao.wayuwa.cn")!) }
    @objc private func refresh() { Task { await store.refresh() } }
    @objc private func rePair() { showPairing(); store.beginPairing() }
    @objc private func quit() { NSApp.terminate(nil) }

    @objc private func toggleLoginItem(_ sender: NSMenuItem) {
        do {
            if SMAppService.mainApp.status == .enabled { try SMAppService.mainApp.unregister() }
            else { try SMAppService.mainApp.register() }
            sender.state = SMAppService.mainApp.status == .enabled ? .on : .off
        } catch {
            let alert = NSAlert(); alert.messageText = "登录启动设置失败"; alert.informativeText = error.localizedDescription
            alert.runModal()
        }
    }
}

final class PetPanel: NSPanel {
    var onClick: (() -> Void)?
    var onDragging: ((Bool) -> Void)?
    var onMoved: (() -> Void)?

    override var canBecomeKey: Bool { false }

    override func mouseDown(with event: NSEvent) {
        let startMouse = NSEvent.mouseLocation
        let startOrigin = frame.origin
        var dragged = false
        onDragging?(true)
        while let next = nextEvent(matching: [.leftMouseDragged, .leftMouseUp]) {
            if next.type == .leftMouseUp { break }
            let current = NSEvent.mouseLocation
            let delta = NSPoint(x: current.x - startMouse.x, y: current.y - startMouse.y)
            if abs(delta.x) > 3 || abs(delta.y) > 3 { dragged = true }
            setFrameOrigin(NSPoint(x: startOrigin.x + delta.x, y: startOrigin.y + delta.y))
        }
        onDragging?(false)
        if dragged { onMoved?() } else { onClick?() }
    }
}
