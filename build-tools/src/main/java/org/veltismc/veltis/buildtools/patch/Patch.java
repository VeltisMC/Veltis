package org.veltismc.veltis.buildtools.patch;

import java.nio.file.Path;

public record Patch(
    String id,
    String description,
    PatchType type,
    Path sourceFile,
    Path targetFile,
    Priority priority
) {

    public enum PatchType {
        SOURCE,
        BINARY,
        RESOURCE,
        CONFIGURATION
    }

    public enum Priority {
        EARLIEST(0),
        EARLY(25),
        NORMAL(50),
        LATE(75),
        LATEST(100);

        private final int order;

        Priority(int order) {
            this.order = order;
        }

        public int order() {
            return order;
        }
    }
}


