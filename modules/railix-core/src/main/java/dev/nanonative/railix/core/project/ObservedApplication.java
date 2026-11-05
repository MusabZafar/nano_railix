package dev.nanonative.railix.core.project;

import dev.nanonative.railix.core.runtime.RuntimeMetrics;

/** An application exposing its own bounded operational metrics, independent of Examples. */
public interface ObservedApplication extends RuntimeApplication {
    /**
     * Returns measurements without executing a Flow.
     * @return the non-null application-owned metric store
     */
    RuntimeMetrics metrics();
}
