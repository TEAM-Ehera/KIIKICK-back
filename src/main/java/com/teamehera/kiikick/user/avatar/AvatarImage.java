package com.teamehera.kiikick.user.avatar;

import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.springframework.web.multipart.MultipartFile;

final class AvatarImage {
    static final long MAX_BYTES = 5L * 1024 * 1024;
    private static final int MAX_DIMENSION = 8192;
    private static final long MAX_PIXELS = 25_000_000;
    private static final int AVATAR_SIZE = 512;

    private AvatarImage() {}

    static byte[] encode(MultipartFile file) {
        if (file.getSize() > MAX_BYTES) throw new TooLarge();
        if (file.isEmpty()) throw new Invalid();

        try (var stream = file.getInputStream(); var input = new MemoryCacheImageInputStream(stream)) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new Invalid();
            var reader = readers.next();
            try {
                String format = reader.getFormatName();
                if (!format.equalsIgnoreCase("JPEG") && !format.equalsIgnoreCase("PNG")) throw new Invalid();
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                // 헤더 크기를 먼저 검사해 작은 압축 파일의 과도한 메모리 사용을 막는다.
                if (width > MAX_DIMENSION || height > MAX_DIMENSION || (long) width * height > MAX_PIXELS) {
                    throw new Invalid();
                }

                var readParameters = reader.getDefaultReadParam();
                int subsample = Math.max(1, Math.max(width, height) / (AVATAR_SIZE * 2));
                readParameters.setSourceSubsampling(subsample, subsample, 0, 0);
                var source = reader.read(0, readParameters);
                double scale = Math.min(1.0, (double) AVATAR_SIZE / Math.max(width, height));
                int targetWidth = Math.max(1, (int) Math.round(width * scale));
                int targetHeight = Math.max(1, (int) Math.round(height * scale));
                var target = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
                var graphics = target.createGraphics();
                try {
                    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    graphics.drawImage(source, 0, 0, targetWidth, targetHeight, Color.WHITE, null);
                } finally {
                    graphics.dispose();
                }
                // 새 JPEG에는 원본 EXIF나 위치 정보가 포함되지 않는다.
                var output = new ByteArrayOutputStream();
                if (!ImageIO.write(target, "JPEG", output)) throw new IllegalStateException("JPEG encoder unavailable");
                return output.toByteArray();
            } finally {
                reader.dispose();
            }
        } catch (IOException | IllegalArgumentException exception) {
            throw new Invalid();
        }
    }

    static final class Invalid extends RuntimeException {}
    static final class TooLarge extends RuntimeException {}
}
