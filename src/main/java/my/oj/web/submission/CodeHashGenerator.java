package my.oj.web.submission;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class CodeHashGenerator {

    private static final String HASH_ALGORITHM = "SHA-256";
    private static final HexFormat HEX_FORMAT = HexFormat.of();

    private CodeHashGenerator() {
    }

    public static String generate(String source) {
        return generateWithAttempt(source, 0);
    }

    public static String generateWithAttempt(String source, int attempt) {
        String candidate = source == null ? "" : source;
        if (attempt > 0) {
            candidate = candidate + "#" + attempt;
        }
        return digest(candidate.getBytes(StandardCharsets.UTF_8));
    }

    private static String digest(byte[] input) {
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            return HEX_FORMAT.formatHex(digest.digest(input));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 MessageDigest not available", e);
        }
    }
}
