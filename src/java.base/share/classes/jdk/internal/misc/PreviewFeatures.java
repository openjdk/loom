package jdk.internal.misc;

public class PreviewFeature {
    private static final boolean ENABLED = VM.isPreviewEnabled();

    public static void ensureEnabled() {
        if (!ENABLED) {
            throw new UnsupportedOperationException("Preview ")
        }

    }

    public static boolean isEnabled() {
        return ENABLED;
    }
}
