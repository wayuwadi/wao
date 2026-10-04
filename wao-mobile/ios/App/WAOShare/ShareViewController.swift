import UIKit
import UniformTypeIdentifiers
import Vision

/// 分享扩展只处理系统实际交付的内容；不强制启动主应用。
final class ShareViewController: UIViewController, UITextViewDelegate {
    private var item = CaptureItem(id: CaptureFormat.newID())
    private let excerpt = UITextView(), thoughts = UITextView(), status = UILabel(), saveButton = UIButton(type: .system)
    private let imageView = UIImageView()
    private let cancel = UIButton(type: .system)
    private var busy = false
    override func viewDidLoad() {
        super.viewDidLoad(); view.backgroundColor = .systemBackground
        let heading = UILabel(); heading.text = "保存到 WAO 小记"; heading.font = .preferredFont(forTextStyle: .title2)
        let quoteLabel = UILabel(); quoteLabel.text = "书摘 · 请核对原文"; quoteLabel.font = .preferredFont(forTextStyle: .headline)
        let thoughtsLabel = UILabel(); thoughtsLabel.text = "我的感想（可留空）"
        excerpt.font = .preferredFont(forTextStyle: .body); thoughts.font = .preferredFont(forTextStyle: .body)
        excerpt.delegate = self; thoughts.delegate = self
        excerpt.backgroundColor = .secondarySystemBackground; thoughts.backgroundColor = .secondarySystemBackground
        excerpt.layer.cornerRadius = 10; thoughts.layer.cornerRadius = 10
        imageView.contentMode = .scaleAspectFit; imageView.isHidden = true
        status.numberOfLines = 0; status.font = .preferredFont(forTextStyle: .footnote)
        status.text = "正在读取分享内容…"
        saveButton.setTitle("保存小记", for: .normal); saveButton.isEnabled = false
        saveButton.addTarget(self, action: #selector(saveNote), for: .touchUpInside)
        cancel.setTitle("收起并保留草稿", for: .normal)
        cancel.addTarget(self, action: #selector(cancelShare), for: .touchUpInside)
        let buttons = UIStackView(arrangedSubviews: [cancel, saveButton]); buttons.distribution = .fillEqually
        let stack = UIStackView(arrangedSubviews: [heading, imageView, quoteLabel, excerpt, thoughtsLabel, thoughts, status, buttons])
        stack.axis = .vertical; stack.spacing = 12; stack.translatesAutoresizingMaskIntoConstraints = false
        let scroll = UIScrollView(); scroll.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(scroll); scroll.addSubview(stack)
        NSLayoutConstraint.activate([
            scroll.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor),
            scroll.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor),
            scroll.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            scroll.bottomAnchor.constraint(equalTo: view.keyboardLayoutGuide.topAnchor),
            stack.leadingAnchor.constraint(equalTo: scroll.contentLayoutGuide.leadingAnchor, constant: 20),
            stack.trailingAnchor.constraint(equalTo: scroll.contentLayoutGuide.trailingAnchor, constant: -20),
            stack.topAnchor.constraint(equalTo: scroll.contentLayoutGuide.topAnchor, constant: 16),
            stack.bottomAnchor.constraint(equalTo: scroll.contentLayoutGuide.bottomAnchor, constant: -12),
            stack.widthAnchor.constraint(equalTo: scroll.frameLayoutGuide.widthAnchor, constant: -40),
            excerpt.heightAnchor.constraint(equalToConstant: 150), thoughts.heightAnchor.constraint(equalToConstant: 90),
            imageView.heightAnchor.constraint(equalToConstant: 100), buttons.heightAnchor.constraint(equalToConstant: 48)
        ])
        Task { await loadShared() }
    }
    @MainActor private func loadShared() async {
        do {
            if let session = try CaptureStore.session() { item.accountId = session.accountId; item.memberId = session.memberId }
            let inputs = extensionContext?.inputItems as? [NSExtensionItem] ?? []
            let providers = inputs.flatMap { $0.attachments ?? [] }
            var texts: [String] = []
            for input in inputs {
                if let content = input.attributedContentText?.string, !content.isEmpty { texts.append(content) }
                if item.sourceTitle.isEmpty, let title = input.attributedTitle?.string, !title.isEmpty { item.sourceTitle = title }
            }
            for provider in providers.prefix(8) {
                if provider.hasItemConformingToTypeIdentifier(UTType.plainText.identifier) {
                    let value = try await load(provider, UTType.plainText.identifier)
                    if let text = value as? String, !texts.contains(text) { texts.append(text) }
                    else if let data = value as? Data, let text = String(data: data, encoding: .utf8), !texts.contains(text) { texts.append(text) }
                }
                if provider.hasItemConformingToTypeIdentifier(UTType.url.identifier) {
                    let value = try await load(provider, UTType.url.identifier)
                    if let url = value as? URL, ["http", "https"].contains(url.scheme?.lowercased() ?? "") { item.sourceUrl = url.absoluteString }
                }
                if provider.hasItemConformingToTypeIdentifier(UTType.image.identifier), item.imagePath == nil {
                    let value = try await load(provider, UTType.image.identifier)
                    let image: UIImage?
                    if let url = value as? URL { image = UIImage(data: try Data(contentsOf: url)) }
                    else if let data = value as? Data { image = UIImage(data: data) }
                    else { image = value as? UIImage }
                    guard let image = image, let jpg = image.jpegData(compressionQuality: 0.92) else { throw CaptureFailure.message("分享图片无法读取") }
                    guard jpg.count <= 10 * 1024 * 1024 else { throw CaptureFailure.message("分享图片超过 10MB，请选择较小图片") }
                    let name = item.id + ".jpg"
                    try jpg.write(to: CaptureStore.imageFile(name), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
                    item.imagePath = name; imageView.image = image; imageView.isHidden = false
                    let recognized = (try? await recognize(jpg)) ?? ""
                    if !recognized.isEmpty { texts.append(recognized) }
                }
            }
            item.excerpt = texts.joined(separator: "\n\n"); excerpt.text = item.excerpt
            guard !item.excerpt.isEmpty || !item.sourceUrl.isEmpty || item.imagePath != nil else { throw CaptureFailure.message("没有收到可保存的文字、链接或图片") }
            try CaptureStore.save(item)
            if item.excerpt.isEmpty && !item.sourceUrl.isEmpty { status.text = "只收到链接，未取得所选书摘。可以保存链接，或取消后复制原文。" }
            else if item.imagePath != nil { status.text = "图片文字是识别结果，请核对；原图会随小记保存。" }
            else { status.text = item.accountId == nil ? "尚未登录 WAO，保存后暂存本机，打开 WAO 再确认归属。" : "书摘保留原文，AI 只整理我的感想。" }
            saveButton.isEnabled = true
        } catch { status.text = error.localizedDescription }
    }
    private func load(_ provider: NSItemProvider, _ type: String) async throws -> NSSecureCoding {
        try await withCheckedThrowingContinuation { continuation in
            provider.loadItem(forTypeIdentifier: type, options: nil) { value, error in
                if let error = error { continuation.resume(throwing: error) }
                else if let value = value { continuation.resume(returning: value) }
                else { continuation.resume(throwing: CaptureFailure.message("分享内容为空")) }
            }
        }
    }
    private func recognize(_ data: Data) async throws -> String {
        try await Task.detached {
            let request = VNRecognizeTextRequest(); request.recognitionLevel = .accurate
            request.recognitionLanguages = ["zh-Hans", "en-US"]; request.usesLanguageCorrection = false
            try VNImageRequestHandler(data: data, options: [:]).perform([request])
            return (request.results ?? []).compactMap { $0.topCandidates(1).first?.string }.joined(separator: "\n")
        }.value
    }
    func textViewDidChange(_ textView: UITextView) {
        guard !busy else { return }
        item.excerpt = excerpt.text; item.thoughts = thoughts.text
        do { try CaptureStore.save(item) }
        catch { status.text = "本机草稿保存失败，请保持面板并重试：" + error.localizedDescription }
    }
    @objc private func saveNote() {
        if busy { return }; busy = true; saveButton.isEnabled = false; excerpt.isEditable = false; thoughts.isEditable = false
        item.excerpt = excerpt.text; item.thoughts = thoughts.text
        guard !item.excerpt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || !item.sourceUrl.isEmpty || item.imagePath != nil else { status.text = "请保留书摘或来源内容"; busy = false; saveButton.isEnabled = true; excerpt.isEditable = true; thoughts.isEditable = true; return }
        do { try CaptureStore.save(item) }
        catch { status.text = "本机暂存失败，请保持面板并重试：" + error.localizedDescription; busy = false; saveButton.isEnabled = true; excerpt.isEditable = true; thoughts.isEditable = true; return }
        status.text = "正在保存…"
        Task { @MainActor in
            do {
                _ = try await CaptureStore.submit(item)
                status.text = "已保存到 WAO，其他设备刷新后可见"
                extensionContext?.completeRequest(returningItems: nil, completionHandler: nil)
            } catch {
                status.text = "仅存本机，尚未上传：" + error.localizedDescription
                cancel.setTitle("关闭并保留草稿", for: .normal)
                saveButton.setTitle("重试上传", for: .normal); saveButton.isEnabled = true; busy = false; excerpt.isEditable = true; thoughts.isEditable = true
            }
        }
    }
    @objc private func cancelShare() {
        guard !busy else { return }
        if let image = item.imagePath, (try? CaptureStore.read(item.id)) == nil { try? FileManager.default.removeItem(at: CaptureStore.imageFile(image)) }
        extensionContext?.completeRequest(returningItems: nil, completionHandler: nil)
    }
}
