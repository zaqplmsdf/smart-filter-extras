package smartfilterextras.client.screen;

import net.minecraft.client.Minecraft;
import net.neoforged.fml.ModList;
import smartfilterextras.SmartFilterExtras;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Substring search that also understands Chinese pinyin (全拼 / 声母) when Just Enough Characters is installed.
 * JECH is looked up reflectively, so it stays an optional, non-compile-time dependency; if its API is missing or
 * throws, this falls back to plain {@code contains} for the rest of the session.
 */
final class SearchText {
    private static final String JECH_MOD_ID = "jecharacters";
    private static volatile MethodHandle jechContains = lookup();

    private SearchText() {
    }

    static boolean contains(String haystack, String needle) {
        MethodHandle handle = jechContains;
        if (handle != null) {
            try {
                return (boolean) handle.invokeExact(haystack, (CharSequence) needle);
            } catch (Throwable t) {
                SmartFilterExtras.LOGGER.warn("Just Enough Characters search failed, using plain search from now on", t);
                jechContains = null;
            }
        }
        return haystack.contains(needle);
    }

    static boolean pinyinAvailable() {
        return jechContains != null;
    }

    private static MethodHandle lookup() {
        if (!ModList.get().isLoaded(JECH_MOD_ID)) {
            return null;
        }
        try {
            Class<?> match = Class.forName("me.towdium.jecharacters.utils.Match", true, Minecraft.class.getClassLoader());
            return MethodHandles.publicLookup().findStatic(match, "contains",
                    MethodType.methodType(boolean.class, String.class, CharSequence.class));
        } catch (ReflectiveOperationException | LinkageError e) {
            SmartFilterExtras.LOGGER.info("Just Enough Characters is installed but its search API was not found; pinyin search is off", e);
            return null;
        }
    }
}
