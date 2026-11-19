import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Icon generator for the simplified vermilion seal mark.
 *
 * <p>Run from the repository root with: {@code java packaging/IconGen.java}
 * The source stays ASCII-only so it can run under any platform default encoding.
 */
public final class IconGen {

    private static final int MASTER = 1024;
    private static final Color ACCENT = new Color(0xD07A66);
    private static final Color IVORY = new Color(0xFFF6EA);
    private static final String SEAL_TEXT = "\u9605\u5fae";

    private IconGen() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Paths.get("").toAbsolutePath();
        BufferedImage master = renderMaster(false);
        BufferedImage masterRound = renderMaster(true);

        Path iconDir = root.resolve("src/main/resources/icon");
        Files.createDirectories(iconDir);
        for (int size : new int[]{16, 32, 64, 128, 256}) {
            writePng(scale(master, size), iconDir.resolve("app-" + size + ".png"));
        }
        writePng(scale(master, 512), iconDir.resolve("app.png"));

        List<BufferedImage> frames = new ArrayList<>();
        for (int size : new int[]{16, 24, 32, 48, 64, 128, 256}) {
            frames.add(scale(master, size));
        }
        Path icoDir = root.resolve("packaging");
        Files.createDirectories(icoDir);
        writeIco(frames, icoDir.resolve("app.ico"));

        String[] densities = {"mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"};
        int[] legacy = {48, 72, 96, 144, 192};
        int[] foreground = {108, 162, 216, 324, 432};
        for (int i = 0; i < densities.length; i++) {
            Path dir = root.resolve("mobile/app/src/main/res/mipmap-" + densities[i]);
            Files.createDirectories(dir);
            writePng(scale(master, legacy[i]), dir.resolve("ic_launcher.png"));
            writePng(scale(masterRound, legacy[i]), dir.resolve("ic_launcher_round.png"));
            writePng(renderForeground(foreground[i]), dir.resolve("ic_launcher_foreground.png"));
        }

        System.out.println("icons generated under " + root);
    }

    private static BufferedImage renderMaster(boolean round) {
        BufferedImage image = new BufferedImage(MASTER, MASTER, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        hints(g);

        Shape clip = round
                ? new Ellipse2D.Double(0, 0, MASTER, MASTER)
                : new RoundRectangle2D.Double(0, 0, MASTER, MASTER, 210, 210);
        g.setClip(clip);
        g.setColor(ACCENT);
        g.fillRect(0, 0, MASTER, MASTER);

        g.setColor(new Color(255, 246, 234, 64));
        g.setStroke(new BasicStroke(9f));
        Shape inner = round
                ? new Ellipse2D.Double(22, 22, MASTER - 44, MASTER - 44)
                : new RoundRectangle2D.Double(22, 22, MASTER - 44, MASTER - 44, 180, 180);
        g.draw(inner);

        drawSealText(g, 0.62, IVORY);
        g.dispose();
        return image;
    }

    /** Adaptive icon foreground: transparent canvas with the seal text only. */
    private static BufferedImage renderForeground(int size) {
        BufferedImage large = new BufferedImage(MASTER, MASTER, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = large.createGraphics();
        hints(g);
        drawSealText(g, 0.56, IVORY);
        g.dispose();
        return scale(large, size);
    }

    private static void drawSealText(Graphics2D g, double maxWidthRatio, Color color) {
        int fontSize = (int) Math.round(MASTER * 0.335);
        Font font = new Font("KaiTi", Font.BOLD, fontSize);
        if (!"KaiTi".equals(font.getFamily())) {
            font = new Font("SimSun", Font.BOLD, fontSize);
        }
        g.setFont(font);
        FontMetrics metrics = g.getFontMetrics();
        double width = metrics.stringWidth(SEAL_TEXT);
        double max = MASTER * maxWidthRatio;
        if (width > max) {
            float next = (float) Math.floor(fontSize * max / width);
            font = font.deriveFont(next);
            g.setFont(font);
            metrics = g.getFontMetrics();
            width = metrics.stringWidth(SEAL_TEXT);
        }
        int x = (int) Math.round((MASTER - width) / 2.0);
        int baseline = (MASTER - (metrics.getAscent() + metrics.getDescent())) / 2
                + metrics.getAscent();
        g.setColor(color);
        g.drawString(SEAL_TEXT, x, baseline);
    }

    private static void hints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    }

    private static BufferedImage scale(BufferedImage source, int size) {
        BufferedImage current = source;
        int currentSize = source.getWidth();
        while (currentSize / 2 >= size) {
            currentSize /= 2;
            BufferedImage next = new BufferedImage(currentSize, currentSize,
                    BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = next.createGraphics();
            hints(g);
            g.drawImage(current, 0, 0, currentSize, currentSize, null);
            g.dispose();
            current = next;
        }
        if (currentSize == size) {
            return current;
        }
        BufferedImage result = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = result.createGraphics();
        hints(g);
        g.drawImage(current, 0, 0, size, size, null);
        g.dispose();
        return result;
    }

    private static void writePng(BufferedImage image, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        try (OutputStream out = Files.newOutputStream(target)) {
            ImageIO.write(image, "png", out);
        }
    }

    /** ICO container with PNG-compressed frames (supported by Windows Vista and later). */
    private static void writeIco(List<BufferedImage> frames, Path target) throws IOException {
        List<byte[]> payloads = new ArrayList<>();
        for (BufferedImage frame : frames) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            ImageIO.write(frame, "png", buffer);
            payloads.add(buffer.toByteArray());
        }
        int headerSize = 6 + 16 * frames.size();
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(target))) {
            writeShortLe(out, 0);
            writeShortLe(out, 1);
            writeShortLe(out, frames.size());
            int offset = headerSize;
            for (int i = 0; i < frames.size(); i++) {
                int size = frames.get(i).getWidth();
                out.writeByte(size >= 256 ? 0 : size);
                out.writeByte(size >= 256 ? 0 : size);
                out.writeByte(0);
                out.writeByte(0);
                writeShortLe(out, 1);
                writeShortLe(out, 32);
                writeIntLe(out, payloads.get(i).length);
                writeIntLe(out, offset);
                offset += payloads.get(i).length;
            }
            for (byte[] payload : payloads) {
                out.write(payload);
            }
        }
    }

    private static void writeShortLe(DataOutputStream out, int value) throws IOException {
        out.writeByte(value & 0xFF);
        out.writeByte((value >>> 8) & 0xFF);
    }

    private static void writeIntLe(DataOutputStream out, int value) throws IOException {
        out.writeByte(value & 0xFF);
        out.writeByte((value >>> 8) & 0xFF);
        out.writeByte((value >>> 16) & 0xFF);
        out.writeByte((value >>> 24) & 0xFF);
    }
}
