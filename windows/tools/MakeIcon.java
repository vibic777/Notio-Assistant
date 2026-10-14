import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;

/**
 * The Windows icon from the Android one (D79), so both show the same drawing: reads the paths of
 * android/app/src/main/res/drawable/ic_launcher_foreground.xml, puts them on the blue background with rounded corners and writes
 * icons/notio.ico (16 - 256 px), icons/notio.png and the window's resources/notio.png (256 px).
 *
 * Run from the repository root after the Android drawing changes (JDK 11+, nothing else needed):
 *     java windows/tools/MakeIcon.java
 */
public class MakeIcon {
    static final Color BACKGROUND = new Color(0x1E5FD8);
    /** The corner radius as a part of the side: no sharp corner at any size. */
    static final double CORNER = 0.28;
    /** The part of the 108-unit Android sheet shown: the drawing with a margin, the launcher's own safe-zone margin left out. */
    static final double VIEW_X = 22, VIEW_Y = 19.5, VIEW_SIZE = 70;
    static final int[] SIZES = {16, 20, 24, 32, 40, 48, 64, 128, 256};

    record Layer(Path2D path, Color fill, Color stroke, float width) {}

    public static void main(String[] args) throws IOException {
        List<Layer> layers = read(new File("android/app/src/main/res/drawable/ic_launcher_foreground.xml"));
        List<byte[]> images = new ArrayList<>();
        for (int size : SIZES) {
            BufferedImage image = draw(layers, size);
            images.add(size == 256 ? png(image) : dib(image));
            if (size == 256) {
                Files.write(new File("windows/desktopApp/icons/notio.png").toPath(), png(image));
                Files.write(new File("windows/desktopApp/src/jvmMain/resources/notio.png").toPath(), png(image));
            }
        }
        Files.write(new File("windows/desktopApp/icons/notio.ico").toPath(), ico(images));
        System.out.println("notio.ico (" + SIZES.length + " sizes), notio.png: " + layers.size() + " paths");
    }

    static BufferedImage draw(List<Layer> layers, int size) {
        // Drawn four times larger and brought down by halves: small sizes keep the thin strokes of the letters.
        int factor = size >= 128 ? 1 : 4;
        int big = size * factor;
        BufferedImage image = new BufferedImage(big, big, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setColor(BACKGROUND);
        double r = big * CORNER * 2;
        g.fill(new RoundRectangle2D.Double(0, 0, big, big, r, r));
        AffineTransform t = new AffineTransform();
        t.scale(big / VIEW_SIZE, big / VIEW_SIZE);
        t.translate(-VIEW_X, -VIEW_Y);
        g.transform(t);
        for (Layer layer : layers) {
            if (layer.fill != null) { g.setColor(layer.fill); g.fill(layer.path); }
            if (layer.stroke != null) {
                g.setColor(layer.stroke);
                g.setStroke(new BasicStroke(layer.width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(layer.path);
            }
        }
        g.dispose();
        while (factor > 1) {
            factor /= 2;
            BufferedImage half = new BufferedImage(size * factor, size * factor, BufferedImage.TYPE_INT_ARGB);
            Graphics2D h = half.createGraphics();
            h.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            h.drawImage(image, 0, 0, half.getWidth(), half.getHeight(), null);
            h.dispose();
            image = half;
        }
        return image;
    }

    // ---- the Android vector drawable ----

    static List<Layer> read(File file) throws IOException {
        String xml = Files.readString(file.toPath()).replaceAll("(?s)<!--.*?-->", "");
        List<Layer> layers = new ArrayList<>();
        Matcher m = Pattern.compile("(?s)<path\\b(.*?)/>").matcher(xml);
        while (m.find()) {
            String a = m.group(1);
            String width = attribute(a, "strokeWidth");
            layers.add(new Layer(path(attribute(a, "pathData")), color(attribute(a, "fillColor")), color(attribute(a, "strokeColor")),
                width == null ? 1f : Float.parseFloat(width)));
        }
        if (layers.isEmpty()) throw new IOException("no paths in " + file);
        return layers;
    }

    static String attribute(String attributes, String name) {
        Matcher m = Pattern.compile("android:" + name + "=\"([^\"]*)\"").matcher(attributes);
        return m.find() ? m.group(1) : null;
    }

    static Color color(String hex) {
        if (hex == null) return null;
        long v = Long.parseLong(hex.substring(1), 16);
        return hex.length() == 9 ? new Color((int) v, true) : new Color((int) v);
    }

    /** The path commands the drawing uses: M L H V A Z and their relative forms. */
    static Path2D path(String data) {
        Path2D.Double path = new Path2D.Double();
        Matcher m = Pattern.compile("[MmLlHhVvAaZz]|-?\\d*\\.?\\d+").matcher(data);
        List<String> tokens = new ArrayList<>();
        while (m.find()) tokens.add(m.group());
        double x = 0, y = 0, startX = 0, startY = 0;
        char command = 'M';
        int i = 0;
        while (i < tokens.size()) {
            String token = tokens.get(i);
            if (Character.isLetter(token.charAt(0))) { command = token.charAt(0); i++; }
            boolean relative = Character.isLowerCase(command);
            switch (Character.toUpperCase(command)) {
                case 'M' -> {
                    x = number(tokens, i++) + (relative ? x : 0); y = number(tokens, i++) + (relative ? y : 0);
                    path.moveTo(x, y); startX = x; startY = y;
                    command = relative ? 'l' : 'L';
                }
                case 'L' -> { x = number(tokens, i++) + (relative ? x : 0); y = number(tokens, i++) + (relative ? y : 0); path.lineTo(x, y); }
                case 'H' -> { x = number(tokens, i++) + (relative ? x : 0); path.lineTo(x, y); }
                case 'V' -> { y = number(tokens, i++) + (relative ? y : 0); path.lineTo(x, y); }
                case 'A' -> {
                    double rx = number(tokens, i++), ry = number(tokens, i++), rotation = number(tokens, i++);
                    boolean large = number(tokens, i++) != 0, sweep = number(tokens, i++) != 0;
                    double ex = number(tokens, i++) + (relative ? x : 0), ey = number(tokens, i++) + (relative ? y : 0);
                    arc(path, x, y, rx, ry, rotation, large, sweep, ex, ey);
                    x = ex; y = ey;
                }
                case 'Z' -> { path.closePath(); x = startX; y = startY; }
                default -> throw new IllegalArgumentException("path command " + command);
            }
        }
        return path;
    }

    static double number(List<String> tokens, int i) { return Double.parseDouble(tokens.get(i)); }

    /** An SVG arc from its end points (the SVG implementation notes, F.6.5); only unrotated arcs are drawn here. */
    static void arc(Path2D path, double x0, double y0, double rx, double ry, double rotation, boolean large, boolean sweep, double x, double y) {
        if (rotation != 0) throw new IllegalArgumentException("rotated arcs are not used in the icon");
        double dx = (x0 - x) / 2, dy = (y0 - y) / 2;
        double scale = dx * dx / (rx * rx) + dy * dy / (ry * ry);
        if (scale > 1) { rx *= Math.sqrt(scale); ry *= Math.sqrt(scale); }
        double top = rx * rx * ry * ry - rx * rx * dy * dy - ry * ry * dx * dx;
        double k = Math.sqrt(Math.max(0, top / (rx * rx * dy * dy + ry * ry * dx * dx))) * (large == sweep ? -1 : 1);
        double cx = k * rx * dy / ry + (x0 + x) / 2, cy = -k * ry * dx / rx + (y0 + y) / 2;
        double from = Math.toDegrees(Math.atan2((y0 - cy) / ry, (x0 - cx) / rx));
        double to = Math.toDegrees(Math.atan2((y - cy) / ry, (x - cx) / rx));
        double extent = to - from;
        if (sweep && extent < 0) extent += 360;
        if (!sweep && extent > 0) extent -= 360;
        // Java counts angles counter-clockwise with y up; the picture has y down.
        Arc2D arc = new Arc2D.Double(cx - rx, cy - ry, rx * 2, ry * 2, -from, -extent, Arc2D.OPEN);
        path.append(arc, true);
        Point2D end = path.getCurrentPoint();
        if (end.distance(x, y) > 1e-6) path.lineTo(x, y);
    }

    // ---- the files ----

    static byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    /** An icon image the old way (a 32-bit bitmap and its empty mask): every reader of .ico files takes it. */
    static byte[] dib(BufferedImage image) {
        int size = image.getWidth();
        int maskRow = (size + 31) / 32 * 4;
        ByteBuffer b = ByteBuffer.allocate(40 + size * size * 4 + maskRow * size).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(40).putInt(size).putInt(size * 2).putShort((short) 1).putShort((short) 32).putInt(0)
            .putInt(size * size * 4 + maskRow * size).putInt(0).putInt(0).putInt(0).putInt(0);
        for (int y = size - 1; y >= 0; y--) for (int x = 0; x < size; x++) b.putInt(image.getRGB(x, y));
        return b.array();
    }

    static byte[] ico(List<byte[]> images) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        ByteBuffer header = ByteBuffer.allocate(6 + 16 * images.size()).order(ByteOrder.LITTLE_ENDIAN);
        header.putShort((short) 0).putShort((short) 1).putShort((short) images.size());
        int offset = header.capacity();
        for (int i = 0; i < images.size(); i++) {
            int size = SIZES[i];
            header.put((byte) (size == 256 ? 0 : size)).put((byte) (size == 256 ? 0 : size)).put((byte) 0).put((byte) 0)
                .putShort((short) 1).putShort((short) 32).putInt(images.get(i).length).putInt(offset);
            offset += images.get(i).length;
        }
        out.write(header.array());
        for (byte[] image : images) out.write(image);
        return bytes.toByteArray();
    }
}
