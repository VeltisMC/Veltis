package org.veltismc.veltis.server.scheduler;

import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

public interface WorldScheduler extends Executor {

    void execute(Runnable task);

    TaskHandle schedule(Runnable task, long delay, TimeUnit unit);

    TaskHandle scheduleRepeating(Runnable task, long delay, long period, TimeUnit unit);

    void cancelAll();

    String worldName();

    Thread worldThread();

    boolean isWorldThread();

    void verifyAccess();

    void shutdown();
}
