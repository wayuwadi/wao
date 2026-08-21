import AppKit

final class SpriteFrames {
    static let shared = SpriteFrames()
    private(set) var frames: [NSImage] = []

    private init() {
        guard let url = Bundle.module.url(forResource: "wao-pixel-flame-sprites-v3", withExtension: "png"),
              let sheet = NSImage(contentsOf: url) else { return }
        let cell = NSSize(width: sheet.size.width / 4, height: sheet.size.height / 3)
        for row in 0..<3 {
            for column in 0..<4 {
                let frame = NSImage(size: cell)
                frame.lockFocus()
                NSGraphicsContext.current?.imageInterpolation = .none
                sheet.draw(in: NSRect(origin: .zero, size: cell),
                           from: NSRect(x: CGFloat(column) * cell.width,
                                        y: CGFloat(2 - row) * cell.height,
                                        width: cell.width, height: cell.height),
                           operation: .copy, fraction: 1)
                frame.unlockFocus()
                frames.append(frame)
            }
        }
    }

    func image(_ index: Int) -> NSImage {
        guard frames.indices.contains(index) else { return NSImage(size: NSSize(width: 1, height: 1)) }
        return frames[index]
    }
}
