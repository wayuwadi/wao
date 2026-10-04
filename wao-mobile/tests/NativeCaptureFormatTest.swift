import Foundation

@main struct NativeCaptureFormatTest {
    static func main() {
        for _ in 0..<50 { precondition(CaptureFormat.newID().range(of: "^[a-f0-9]{32}$", options: .regularExpression) != nil, "必须满足真实小记 API 的 32 位编号约束") }
        var item = CaptureItem(id: CaptureFormat.newID())
        item.excerpt = "*原文*\r\n\r\n<script> &lt;"
        item.thoughts = "自己的想法\n\n第二段"
        precondition(CaptureFormat.markdown(item) == "## 书摘\n\n> \\*原文\\*\n> \n> &lt;script&gt; &amp;lt;\n\n## 我的感想\n\n自己的想法\n\n第二段", "原文、空行与危险 Markdown 字符必须保留")
        item.sourceUrl = "https://example.org/book"; item.imageUrl = "/api/images/original/raw"; item.thoughts = ""
        let markdown = CaptureFormat.markdown(item)
        precondition(markdown.hasPrefix("![书摘原图](/api/images/original/raw)\n\n## 书摘"))
        precondition(markdown.contains("来源：https://example\\.org/book"))
        precondition(markdown.hasSuffix("## 我的感想\n\n"), "空感想不能丢失分隔边界")
        print("原生采集格式、编号和空感想检查通过")
    }
}
