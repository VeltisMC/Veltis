package org.veltismc.api;

public interface Server {
    String getVersion();
    void broadcast(String message);
    // add any other API methods
}