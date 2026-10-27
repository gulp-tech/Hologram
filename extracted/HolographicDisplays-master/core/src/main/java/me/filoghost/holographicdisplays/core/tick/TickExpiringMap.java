/*
 * Copyright (C) filoghost and contributors
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package me.filoghost.holographicdisplays.core.tick;

import java.util.Iterator;
import java.util.Map;
import java.util.function.BiPredicate;

public class TickExpiringMap<K, V extends TickExpiringValue> {

    private final Map<K, V> map;

    // Entries that are not used for this amount of ticks are removed
    private final long expirationTicks;

    public TickExpiringMap(Map<K, V> map, long expirationTicks) {
        this.map = map;
        this.expirationTicks = expirationTicks;
    }

    public void removeEntries(BiPredicate<? super K, ? super V> filter) {
        // An explicit loop is used instead of Map#removeIf, which wraps the predicate into a new lambda
        // on every call: this method runs for every hologram line which contains placeholders.
        Iterator<Map.Entry<K, V>> iterator = map.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<K, V> entry = iterator.next();
            if (filter.test(entry.getKey(), entry.getValue())) {
                iterator.remove();
            }
        }
    }

    public void clearUnusedEntries(long currentTick) {
        Iterator<V> iterator = map.values().iterator();
        while (iterator.hasNext()) {
            if (currentTick - iterator.next().getLastUseTick() >= expirationTicks) {
                iterator.remove();
            }
        }
    }

    public V get(K key) {
        return map.get(key);
    }

    public void put(K key, V value) {
        map.put(key, value);
    }

}
