package org.veltismc.veltis.server.branding;

public final class ServerBrandingService {

    public static final String SERVER_BRAND = "VeltisMC";
    public static final String SERVER_VERSION = "VeltisMC 1.0.0";
    public static final String IMPLEMENTATION_VERSION = "VeltisMC-26.2";
    public static final String FULL_VERSION_STRING = "VeltisMC 1.0.0 (MC 26.2)";

    private ServerBrandingService() {
    }

    public static String serverBrand() {
        return SERVER_BRAND;
    }

    public static String serverVersion() {
        return SERVER_VERSION;
    }

    public static String implementationVersion() {
        return IMPLEMENTATION_VERSION;
    }

    public static String fullVersionString() {
        return FULL_VERSION_STRING;
    }
}



