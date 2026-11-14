package com.juren233.hyperlyricsenhanced.root

/** Deduplicate repeated bind notifications while accepting genuine reconnects. */
internal class ServiceBindingTracker<T : Any> {
    private var current: T? = null

    @Synchronized fun bind(service: T): Boolean {
        if (current === service) return false
        current = service
        return true
    }

    @Synchronized fun died(service: T): Boolean {
        if (current !== service) return false
        current = null
        return true
    }
}
