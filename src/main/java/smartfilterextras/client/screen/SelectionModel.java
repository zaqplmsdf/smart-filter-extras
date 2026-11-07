package smartfilterextras.client.screen;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * An ordered, capacity-limited set of selected entries, with paging helpers. No Minecraft dependencies, so the
 * selection rules can be tested on their own.
 */
public final class SelectionModel<T> {
    public enum Change {
        ADDED, REMOVED, ALREADY_PRESENT, NOT_PRESENT, FULL;

        public boolean changed() {
            return this == ADDED || this == REMOVED;
        }
    }

    private final LinkedHashSet<T> entries = new LinkedHashSet<>();
    private int capacity;

    public SelectionModel(int capacity) {
        this.capacity = capacity;
    }

    public Change add(T entry) {
        if (entries.contains(entry)) {
            return Change.ALREADY_PRESENT;
        }
        if (entries.size() >= capacity) {
            return Change.FULL;
        }
        entries.add(entry);
        return Change.ADDED;
    }

    public Change remove(T entry) {
        return entries.remove(entry) ? Change.REMOVED : Change.NOT_PRESENT;
    }

    public Change toggle(T entry) {
        return entries.contains(entry) ? remove(entry) : add(entry);
    }

    /** Adds entries in order until the capacity is reached; returns how many were added. */
    public int addAll(Collection<? extends T> toAdd) {
        int added = 0;
        for (T entry : toAdd) {
            if (add(entry) == Change.ADDED) {
                added++;
            }
        }
        return added;
    }

    public void clear() {
        entries.clear();
    }

    public boolean contains(T entry) {
        return entries.contains(entry);
    }

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public int capacity() {
        return capacity;
    }

    /** Lowering the capacity never removes existing entries; it only blocks further additions. */
    public void setCapacity(int capacity) {
        this.capacity = capacity;
    }

    public List<T> asList() {
        return new ArrayList<>(entries);
    }

    public int pageCount(int pageSize) {
        return Math.max(1, (entries.size() + pageSize - 1) / pageSize);
    }

    public int clampPage(int page, int pageSize) {
        return Math.max(0, Math.min(page, pageCount(pageSize) - 1));
    }

    public List<T> page(int page, int pageSize) {
        List<T> all = asList();
        int from = clampPage(page, pageSize) * pageSize;
        return all.subList(Math.min(from, all.size()), Math.min(from + pageSize, all.size()));
    }
}
