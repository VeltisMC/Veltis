package org.veltismc.veltis.patchengine;

public class PatchEngineException extends RuntimeException {
    public PatchEngineException(String message) {
        super(message);
    }

    public PatchEngineException(String message, Throwable cause) {
        super(message, cause);
    }
}
