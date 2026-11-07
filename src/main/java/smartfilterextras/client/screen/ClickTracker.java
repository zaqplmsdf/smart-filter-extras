package smartfilterextras.client.screen;

import java.util.Objects;

/**
 * Detects double clicks: two clicks on the same key within a time window. A completed double click resets the
 * tracker, so a third quick click starts a new sequence instead of counting as another double click.
 */
public final class ClickTracker {
    public static final long DEFAULT_WINDOW_MS = 250L;

    private final long windowMillis;
    private Object lastKey;
    private long lastTime;

    public ClickTracker() {
        this(DEFAULT_WINDOW_MS);
    }

    public ClickTracker(long windowMillis) {
        this.windowMillis = windowMillis;
    }

    /** Records a click and returns {@code true} if it completes a double click on {@code key}. */
    public boolean click(Object key, long nowMillis) {
        boolean isDouble = lastKey != null && Objects.equals(key, lastKey) && nowMillis - lastTime < windowMillis;
        if (isDouble) {
            lastKey = null;
        } else {
            lastKey = key;
            lastTime = nowMillis;
        }
        return isDouble;
    }

    public void reset() {
        lastKey = null;
    }
}
