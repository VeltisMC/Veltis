package io.papermc.paper.configuration;

public final class GlobalConfiguration {
    private static final GlobalConfiguration INSTANCE = new GlobalConfiguration();

    public static GlobalConfiguration get() {
        return INSTANCE;
    }

    public Spark spark = new Spark();

    public static final class Spark {
        public boolean enabled = true;
        public boolean enableImmediately = false;
    }
}
