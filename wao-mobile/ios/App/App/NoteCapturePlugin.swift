import UIKit
import WebKit
import Capacitor
import AVFoundation
import Speech
import CryptoKit

final class NotesBridgeViewController: CAPBridgeViewController {
    override func capacitorDidLoad() { bridge?.registerPluginInstance(NoteCapturePlugin()) }
}

@objc(NoteCapturePlugin)
public final class NoteCapturePlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "NoteCapturePlugin"
    public let jsName = "NoteCapture"
    public let pluginMethods: [CAPPluginMethod] = [
        "syncSession", "clearSession", "clipboardStatus", "readClipboard", "pending", "acknowledge", "claim", "saveImport", "previewImport", "copyImport",
        "startRecording", "stopRecording", "recordings", "transcribe", "removeRecording"
    ].map { CAPPluginMethod(name: $0, returnType: CAPPluginReturnPromise) }
    private var recorder: AVAudioRecorder?
    private var audioId: String?
    private var audioOwner: String?
    private var speechTask: SFSpeechRecognitionTask?
    private var speechCall: CAPPluginCall?
    private var speechTimeout: DispatchWorkItem?
    private var observers: [NSObjectProtocol] = []
    private var sessionGeneration = 0
    private var recordingStarting = false
    private var recordingLimit: Timer?

    public override func load() {
        observers.append(NotificationCenter.default.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { [weak self] _ in self?.interrupt() })
        observers.append(NotificationCenter.default.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] _ in self?.interrupt() })
    }
    deinit { observers.forEach { NotificationCenter.default.removeObserver($0) }; recordingLimit?.invalidate() }
    private func interrupt() {
        guard let id = stopAudio() else { return }
        notifyListeners("recordingStopped", data: ["audioId": id], retainUntilConsumed: true)
    }
    private func stopAudio() -> String? {
        guard let id = audioId else { return nil }
        recordingLimit?.invalidate(); recordingLimit = nil
        recorder?.stop(); recorder = nil; audioId = nil; audioOwner = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        return id
    }
    @objc func clipboardStatus(_ call: CAPPluginCall) {
        DispatchQueue.main.async { call.resolve(["hasText": UIPasteboard.general.hasStrings, "change": UIPasteboard.general.changeCount]) }
    }
    @objc func readClipboard(_ call: CAPPluginCall) {
        // 显式点击导入后读取；服从 iOS 跨应用粘贴授权，拒绝时不读取或上传。
        DispatchQueue.main.async {
            guard let text = UIPasteboard.general.string, !text.isEmpty else { call.reject("剪贴板没有可读取文字，请允许粘贴后重试"); return }
            call.resolve(["text": text, "change": UIPasteboard.general.changeCount])
        }
    }
    @objc func syncSession(_ call: CAPPluginCall) {
        DispatchQueue.main.async { self.syncSessionOnMain(call) }
    }
    private func syncSessionOnMain(_ call: CAPPluginCall) {
        sessionGeneration += 1; let ticket = sessionGeneration
        guard let account = call.getString("accountId"), let member = call.getString("memberId") else { call.reject("缺少登录成员"); return }
        let cookies = HTTPCookieStorage.shared.cookies(for: URL(string: CaptureStore.origin)!) ?? []
        guard let cookie = cookies.first(where: { $0.name == "WAO_SESSION" && $0.isSecure }) else {
            try? CaptureStore.clearSession(); call.reject("主应用登录会话尚不可供分享使用"); return
        }
        let session = CaptureSession(accountId: account, memberId: member, cookie: cookie.value,
            expiresAt: cookie.expiresDate ?? Date().addingTimeInterval(3600))
        // 切换账号时立即撤销旧共享登录，避免网络验证期间继续使用旧身份。
        if let previous = try? CaptureStore.session(), previous.accountId != account || previous.memberId != member { try? CaptureStore.clearSession() }
        Task { @MainActor in
            do {
                try await CaptureStore.validate(session)
                guard ticket == sessionGeneration else { throw CaptureFailure.message("登录成员已变化，请重新打开导入") }
                try CaptureStore.setSession(session); call.resolve()
            }
            catch { call.reject(error.localizedDescription) }
        }
    }
    @objc func clearSession(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            self.sessionGeneration += 1
            do { try CaptureStore.clearSession(); self.interrupt(); self.speechTask?.cancel(); self.finishSpeech(error: "登录已退出，录音保留在本机"); call.resolve() }
            catch { call.reject(error.localizedDescription) }
        }
    }
    @objc func pending(_ call: CAPPluginCall) {
        do {
            let items = try CaptureStore.pending(account: call.getString("accountId") ?? "", member: call.getString("memberId") ?? "")
            let json = try JSONSerialization.jsonObject(with: JSONEncoder().encode(items)) as! [[String: Any]]
            call.resolve(["items": json.map { value in var item = value; item["importedId"] = value["id"]; return item }])
        } catch { call.reject(error.localizedDescription) }
    }
    @objc func acknowledge(_ call: CAPPluginCall) {
        do { try CaptureStore.acknowledge(call.getString("id") ?? "", account: call.getString("accountId") ?? "", member: call.getString("memberId") ?? ""); call.resolve() }
        catch { call.reject(error.localizedDescription) }
    }
    @objc func claim(_ call: CAPPluginCall) {
        do {
            var item = try CaptureStore.read(call.getString("id") ?? "")
            guard item.accountId == nil && item.memberId == nil, let session = try CaptureStore.session(),
                  session.accountId == call.getString("accountId"), session.memberId == call.getString("memberId") else {
                throw CaptureFailure.message("请先登录，再明确领取这条未分配导入")
            }
            item.accountId = session.accountId; item.memberId = session.memberId; try CaptureStore.save(item); call.resolve()
        } catch { call.reject(error.localizedDescription) }
    }
    @objc func saveImport(_ call: CAPPluginCall) {
        do {
            var item = try CaptureStore.read(call.getString("id") ?? "")
            guard item.accountId == call.getString("accountId"), item.memberId == call.getString("memberId") else { throw CaptureFailure.message("导入属于其他成员") }
            item.title = call.getString("title") ?? item.title; item.thoughts = call.getString("thoughts") ?? item.thoughts
            try CaptureStore.save(item)
            Task { do { let note = try await CaptureStore.submit(item); call.resolve(["note": note]) } catch { call.reject(error.localizedDescription) } }
        } catch { call.reject(error.localizedDescription) }
    }
    private func ownedItem(_ call: CAPPluginCall) throws -> CaptureItem {
        let item = try CaptureStore.read(call.getString("id") ?? "")
        guard item.accountId == call.getString("accountId"), item.memberId == call.getString("memberId") else { throw CaptureFailure.message("导入属于其他成员") }
        return item
    }
    @objc func previewImport(_ call: CAPPluginCall) {
        do {
            let item = try ownedItem(call)
            let data = try item.imagePath.map { try Data(contentsOf: CaptureStore.imageFile($0)) }
            call.resolve(["dataUrl": data.map { "data:image/jpeg;base64," + $0.base64EncodedString() } ?? ""])
        } catch { call.reject(error.localizedDescription) }
    }
    @objc func copyImport(_ call: CAPPluginCall) {
        do {
            var item = try ownedItem(call); let id = call.getString("newId") ?? ""
            try CaptureStore.validID(id)
            guard !FileManager.default.fileExists(atPath: try CaptureStore.file(id).path) else { throw CaptureFailure.message("草稿编号已存在") }
            if let image = item.imagePath {
                let name = id + ".jpg"; try FileManager.default.copyItem(at: CaptureStore.imageFile(image), to: CaptureStore.imageFile(name)); item.imagePath = name
            }
            item.id = id; try CaptureStore.save(item); call.resolve()
        } catch { call.reject(error.localizedDescription) }
    }
    private func recordingsDirectory(_ owner: String) throws -> URL {
        guard !owner.isEmpty, owner.count <= 300 else { throw CaptureFailure.message("录音成员无效") }
        let hash = SHA256.hash(data: Data(owner.utf8)).map { String(format: "%02x", $0) }.joined()
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let folder = base.appendingPathComponent("NoteAudio/" + hash, isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true, attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
        return folder
    }
    private func recordingURL(_ owner: String, _ id: String) throws -> URL {
        try CaptureStore.validID(id); return try recordingsDirectory(owner).appendingPathComponent(id + ".m4a")
    }
    @objc func startRecording(_ call: CAPPluginCall) {
        guard let owner = call.getString("owner"), let id = call.getString("id") else { call.reject("缺少录音编号"); return }
        DispatchQueue.main.async {
            guard self.recorder == nil && !self.recordingStarting else { call.reject("已有录音正在进行"); return }
            self.recordingStarting = true; let ticket = self.sessionGeneration
            AVAudioSession.sharedInstance().requestRecordPermission { allowed in
                DispatchQueue.main.async {
                    self.recordingStarting = false
                    guard ticket == self.sessionGeneration, UIApplication.shared.applicationState == .active else { call.reject("应用已切走或成员已变化，请重新录音"); return }
                    guard allowed else { call.reject("麦克风权限未允许，可在系统设置中允许 WAO 使用麦克风"); return }
                    do {
                        let url = try self.recordingURL(owner, id)
                        guard !FileManager.default.fileExists(atPath: url.path) else { throw CaptureFailure.message("录音编号已存在，未覆盖原音频") }
                        let session = AVAudioSession.sharedInstance()
                        try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker, .allowBluetooth])
                        try session.setActive(true)
                        let recorder = try AVAudioRecorder(url: url, settings: [AVFormatIDKey: kAudioFormatMPEG4AAC, AVSampleRateKey: 44100, AVNumberOfChannelsKey: 1, AVEncoderAudioQualityKey: AVAudioQuality.high.rawValue])
                        guard recorder.record() else { throw CaptureFailure.message("录音未能开始") }
                        self.recorder = recorder; self.audioId = id; self.audioOwner = owner; call.resolve()
                        self.recordingLimit = Timer.scheduledTimer(withTimeInterval: 60, repeats: false) { [weak self] _ in self?.interrupt() }
                    } catch { try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation); call.reject(error.localizedDescription) }
                }
            }
        }
    }
    @objc func stopRecording(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            guard let id = self.stopAudio() else { call.reject("没有正在录制的音频；已有录音仍保留在本机"); return }
            call.resolve(["audioId": id])
        }
    }
    @objc func recordings(_ call: CAPPluginCall) {
        do { let urls = try FileManager.default.contentsOfDirectory(at: recordingsDirectory(call.getString("owner") ?? ""), includingPropertiesForKeys: nil); call.resolve(["ids": urls.filter { $0.pathExtension == "m4a" }.map { $0.deletingPathExtension().lastPathComponent }]) }
        catch { call.reject(error.localizedDescription) }
    }
    @objc func transcribe(_ call: CAPPluginCall) {
        guard let owner = call.getString("owner"), let id = call.getString("audioId") else { call.reject("缺少录音编号"); return }
        DispatchQueue.main.async {
            guard self.speechCall == nil else { call.reject("已有转写正在进行，请稍后重试"); return }
            self.speechCall = call
            SFSpeechRecognizer.requestAuthorization { authorization in
                DispatchQueue.main.async {
                    guard self.speechCall === call else { return }
                    guard authorization == .authorized else { self.finishSpeech(error: "语音识别权限未允许，录音已保留，可在系统设置中开启"); return }
                    do {
                        let url = try self.recordingURL(owner, id)
                        guard FileManager.default.fileExists(atPath: url.path), let recognizer = SFSpeechRecognizer(locale: Locale(identifier: "zh-CN")), recognizer.isAvailable else { throw CaptureFailure.message("语音识别暂不可用，录音已保留") }
                        let request = SFSpeechURLRecognitionRequest(url: url); request.shouldReportPartialResults = false
                        if recognizer.supportsOnDeviceRecognition { request.requiresOnDeviceRecognition = true }
                        let timeout = DispatchWorkItem { [weak self] in guard self?.speechCall === call else { return }; self?.speechTask?.cancel(); self?.finishSpeech(error: "转写等待超时，录音已保留，请重试") }
                        self.speechTimeout = timeout; DispatchQueue.main.asyncAfter(deadline: .now() + 90, execute: timeout)
                        self.speechTask = recognizer.recognitionTask(with: request) { result, error in
                            DispatchQueue.main.async {
                                guard self.speechCall === call else { return }
                                if let result = result, result.isFinal { self.finishSpeech(text: result.bestTranscription.formattedString) }
                                else if let error = error { self.finishSpeech(error: "转写未完成，录音已保留：" + error.localizedDescription) }
                            }
                        }
                    } catch { self.finishSpeech(error: error.localizedDescription) }
                }
            }
        }
    }
    private func finishSpeech(text: String? = nil, error: String? = nil) {
        guard let call = speechCall else { return }
        speechCall = nil; speechTimeout?.cancel(); speechTimeout = nil; speechTask = nil
        if let error = error { call.reject(error) } else { call.resolve(["text": text ?? ""]) }
    }
    @objc func removeRecording(_ call: CAPPluginCall) {
        do { try FileManager.default.removeItem(at: recordingURL(call.getString("owner") ?? "", call.getString("audioId") ?? "")); call.resolve() }
        catch { call.reject(error.localizedDescription) }
    }
}
