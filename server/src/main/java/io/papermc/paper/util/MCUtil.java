package io.papermc.paper.util;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MCUtil {
    private static final ExecutorService ASYNC_EXECUTOR = Executors.newFixedThreadPool(2,
        new ThreadFactoryBuilder().setNameFormat("Paper Async Task Handler Thread - %1$d").setDaemon(true).build());

    public static void scheduleAsyncTask(Runnable run) {
        ASYNC_EXECUTOR.execute(run);
    }

    private MCUtil() {
    }
}
