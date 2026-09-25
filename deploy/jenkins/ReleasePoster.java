import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;

/** Jenkins 成功发布图：只排版真实批次标题，入口只读取同一提交中的 WAO 路由。 */
public final class ReleasePoster {
    private static final int WIDTH = 1200;
    private static final Color WHITE = new Color(245, 248, 255);
    private static final Color MUTED = new Color(163, 183, 222);
    private static final Pattern TITLE = Pattern.compile(
            "^-\\s*(feat|fix|perf|refactor|docs|chore|build|ci)\\(([^)]+)\\):\\s*(.+)$");
    private static final String BASE = "https://wao.wayuwa.cn";

    private ReleasePoster() { }

    public static void main(String[] args) throws Exception {
        if (args.length == 3 && "--describe".equals(args[0])) {
            for (Item item : items(Files.readString(Path.of(args[1])), args[2])) {
                System.out.println(item.kind() + "\t" + item.label() + "\t"
                        + item.description() + "\t" + item.entry());
            }
            return;
        }
        if (args.length != 7 || !"--render".equals(args[0])) {
            throw new IllegalArgumentException(
                    "Usage: ReleasePoster --render OUT NAV TIME BUILD SHA CHANGES");
        }
        List<Item> changes = items(Files.readString(Path.of(args[2])), args[6]);
        render(Path.of(args[1]), args[3], args[4], args[5], changes);
    }

    private static List<Item> items(String navigation, String changes) {
        List<Item> result = new ArrayList<>();
        for (String line : changes.split("\\R")) {
            Matcher title = TITLE.matcher(line.trim());
            if (!title.matches()) continue;
            String scope = title.group(2).trim();
            String kind = switch (title.group(1)) {
                case "feat" -> "新功能";
                case "fix" -> "问题修复";
                case "perf" -> "体验提速";
                default -> "维护更新";
            };
            String[] route = route(navigation, scope);
            result.add(new Item(kind, route[0].isEmpty() ? "WAO 更新" : route[0],
                    clean(title.group(3)), route[1]));
        }
        if (result.isEmpty()) result.add(new Item("更新摘要", "本次发布",
                "改动详情请查看 Jenkins 构建记录", ""));
        return result;
    }

    private static String[] route(String source, String scope) {
        int start = source.indexOf("export const MODULES:");
        if (start < 0) return new String[] {"", ""};
        source = source.substring(start);
        Pattern object = Pattern.compile("(?s)(?:^|\\n)\\s*(?:'"
                + Pattern.quote(scope) + "'|" + Pattern.quote(scope) + ")\\s*:\\s*\\{([^}]*)\\}");
        Matcher match = object.matcher(source);
        if (!match.find()) return new String[] {"", ""};
        String body = match.group(1);
        Matcher label = Pattern.compile("label:\\s*'([^']+)'", Pattern.MULTILINE).matcher(body);
        Matcher path = Pattern.compile("path:\\s*'(/[^']*)'", Pattern.MULTILINE).matcher(body);
        if (!label.find() || !path.find()) return new String[] {"", ""};
        return new String[] {label.group(1), BASE + path.group(1)};
    }

    private static String clean(String value) {
        return value.replaceAll("[\\p{Cntrl}]+", " ").trim();
    }

    private static Font font(int style, int size) throws Exception {
        Path bundled = Path.of("deploy/jenkins/fonts/WAO-Release-Sans.ttf");
        Font base = Font.createFont(Font.TRUETYPE_FONT, bundled.toFile());
        return base.deriveFont(style, (float) size);
    }

    private static void render(Path output, String time, String build, String sha,
            List<Item> items) throws Exception {
        Font body = font(Font.PLAIN, 35);
        StringBuilder probe = new StringBuilder("参考图库已上线 功能入口 构建版本 新功能问题修复体验提速维护更新…");
        for (Item item : items) probe.append(item.label()).append(item.description()).append(item.entry());
        String fontProbe = probe.toString();
        int missing = body.canDisplayUpTo(fontProbe);
        if (missing != -1) {
            throw new IOException("发布图字库缺少 U+"
                    + Integer.toHexString(fontProbe.codePointAt(missing)));
        }
        int height = items.size() <= 1 ? 850 : items.size() == 2 ? 1080 : 1320;
        BufferedImage image = new BufferedImage(WIDTH, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setPaint(new GradientPaint(0, 0, new Color(8, 21, 55), WIDTH, height,
                new Color(13, 31, 75)));
        g.fillRect(0, 0, WIDTH, height);
        g.setColor(new Color(47, 88, 185, 45));
        g.fillOval(705, -185, 650, 650);
        g.setColor(new Color(87, 60, 174, 45));
        g.fillOval(-250, height - 550, 670, 670);

        g.setColor(new Color(123, 159, 255));
        g.setFont(font(Font.BOLD, 29));
        g.drawString("WAO  ·  RELEASE NOTE", 76, 100);
        boolean feature = items.stream().anyMatch(item -> item.kind().equals("新功能"));
        g.setColor(WHITE);
        g.setFont(font(Font.BOLD, 76));
        g.drawString(feature ? "新功能已上线" : "WAO 已更新", 73, 209);
        g.setColor(MUTED);
        g.setFont(font(Font.PLAIN, 31));
        g.drawString("本次发布的改动，一图看懂", 76, 270);

        g.setColor(new Color(127, 166, 255));
        g.setStroke(new BasicStroke(3));
        g.draw(new RoundRectangle2D.Double(818, 83, 295, 220, 34, 34));
        g.setColor(new Color(43, 70, 142));
        g.fillRoundRect(856, 114, 212, 129, 22, 22);
        g.setColor(new Color(151, 180, 255));
        g.fillRoundRect(879, 141, 95, 12, 6, 6);
        g.fillRoundRect(879, 170, 150, 9, 5, 5);
        g.setColor(new Color(89, 235, 206));
        g.fillOval(987, 128, 40, 40);

        int shown = Math.min(3, items.size());
        int y = 348;
        for (int i = 0; i < shown; i++) {
            Item item = items.get(i);
            int cardHeight = 234;
            g.setColor(new Color(22, 43, 92));
            g.fillRoundRect(72, y, 1056, cardHeight, 30, 30);
            g.setColor(new Color(54, 84, 151));
            g.setStroke(new BasicStroke(2));
            g.drawRoundRect(72, y, 1056, cardHeight, 30, 30);
            g.setColor(item.kind().equals("新功能") ? new Color(86, 232, 210)
                    : new Color(155, 182, 255));
            g.setFont(font(Font.BOLD, 24));
            g.drawString(item.kind(), 108, y + 45);
            g.setColor(WHITE);
            g.setFont(font(Font.BOLD, 39));
            g.drawString(ellipsize(g, item.label(), 925), 108, y + 98);
            g.setColor(new Color(207, 218, 243));
            g.setFont(body);
            drawLines(g, item.description(), 108, y + 145, 925, 2, 40);
            if (!item.entry().isEmpty()) {
                g.setColor(new Color(151, 181, 250));
                g.setFont(font(Font.PLAIN, 25));
                g.drawString(ellipsize(g, "入口  " + item.entry(), 925), 108, y + 207);
            }
            y += cardHeight + 18;
        }
        if (items.size() > shown) {
            g.setColor(MUTED);
            g.setFont(font(Font.PLAIN, 28));
            g.drawString("还有 " + (items.size() - shown) + " 项改动 · 打开 Jenkins 查看完整清单", 80, y + 25);
        }

        g.setColor(new Color(81, 113, 197));
        g.fillRoundRect(72, height - 139, 1056, 1, 1, 1);
        g.setColor(MUTED);
        g.setFont(font(Font.PLAIN, 26));
        g.drawString("构建 #" + clean(build) + "   ·   " + clean(time), 76, height - 85);
        g.drawString("版本 " + clean(sha).substring(0, Math.min(8, clean(sha).length())), 76, height - 44);
        g.setColor(new Color(126, 163, 255));
        g.setFont(font(Font.BOLD, 27));
        g.drawString("WAO", 1044, height - 52);
        g.dispose();
        Files.createDirectories(output.toAbsolutePath().getParent());
        if (!ImageIO.write(image, "png", output.toFile())) throw new IOException("PNG 编码失败");
    }

    private static void drawLines(Graphics2D g, String text, int x, int y, int width,
            int maxLines, int lineHeight) {
        List<String> lines = new ArrayList<>();
        String remaining = text;
        while (!remaining.isEmpty() && lines.size() < maxLines) {
            FontMetrics metrics = g.getFontMetrics();
            int end = 0;
            while (end < remaining.length()
                    && metrics.stringWidth(remaining.substring(0, end + 1)) <= width) end++;
            if (end == 0) end = 1;
            lines.add(remaining.substring(0, end));
            remaining = remaining.substring(end);
        }
        if (!remaining.isEmpty() && !lines.isEmpty()) {
            int last = lines.size() - 1;
            lines.set(last, ellipsize(g, lines.get(last) + remaining, width));
        }
        for (String line : lines) {
            g.drawString(line, x, y);
            y += lineHeight;
        }
    }

    private static String ellipsize(Graphics2D g, String text, int width) {
        FontMetrics metrics = g.getFontMetrics();
        if (metrics.stringWidth(text) <= width) return text;
        while (!text.isEmpty() && metrics.stringWidth(text + "…") > width) {
            text = text.substring(0, text.length() - 1);
        }
        return text + "…";
    }

    private record Item(String kind, String label, String description, String entry) { }
}
