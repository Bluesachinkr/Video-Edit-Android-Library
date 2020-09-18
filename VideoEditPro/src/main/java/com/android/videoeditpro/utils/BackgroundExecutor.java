package com.android.videoeditpro.utils;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class BackgroundExecutor {

    private static final String TAG = "BackgroundExecutor";

    public static final Executor DEFAULT_EXECUTOR = Executors.newScheduledThreadPool(2 * Runtime.getRuntime().availableProcessors());
    private static Executor executor = DEFAULT_EXECUTOR;
    private static final List<Task> TASKS = new ArrayList<>();
    private static final ThreadLocal<String> CURRENT_SERIAL = new ThreadLocal<>();

    private BackgroundExecutor() {
    }

    private static Future<?> directExecute(Runnable runnable, long delay) {
        Future<?> future = null;
        if (delay > 0) {
            /* no serial, but a delay: schedule the task */
            if (!(executor instanceof ScheduledExecutorService)) {
                throw new IllegalArgumentException("The executor set does not support scheduling");
            }
            ScheduledExecutorService scheduledExecutorService = (ScheduledExecutorService) executor;
            future = scheduledExecutorService.schedule(runnable, delay, TimeUnit.MILLISECONDS);
        } else {
            if (executor instanceof ExecutorService) {
                ExecutorService executorService = (ExecutorService) executor;
                future = executorService.submit(runnable);
            } else {
                /* non-cancellable task */
                executor.execute(runnable);
            }
        }
        return future;
    }

    public static synchronized void execute(Task task) {
        Future<?> future = null;
        if (task.serial == null || !hasSerialRunning(task.serial)) {
            task.executionAsked = true;
            future = directExecute(task, task.remainingDelay);
        }
        if ((task.id != null || task.serial != null) && !task.managed.get()) {
            /* keep task */
            task.future = future;
            TASKS.add(task);
        }
    }

    private static boolean hasSerialRunning(String serial) {
        for (Task task : TASKS) {
            if (task.executionAsked && serial.equals(task.serial)) {
                return true;
            }
        }
        return false;
    }


    private static Task take(String serial) {
        int len = TASKS.size();
        for (int i = 0; i < len; i++) {
            if (serial.equals(TASKS.get(i).serial)) {
                return TASKS.remove(i);
            }
        }
        return null;
    }

    public static synchronized void cancelAll(String id, boolean mayInterruptIfRunning) {
        for (int i = TASKS.size() - 1; i >= 0; i--) {
            Task task = TASKS.get(i);
            if (id.equals(task.id)) {
                if (task.future != null) {
                    task.future.cancel(mayInterruptIfRunning);
                    if (!task.managed.getAndSet(true)) {
                        task.postExecute();
                    }
                } else if (task.executionAsked) {
                    Log.w(TAG, "A task with id " + task.id + " cannot be cancelled (the executor set does not support it)");
                } else {
                    TASKS.remove(i);
                }
            }
        }
    }

    public static abstract class Task implements Runnable {

        private String id;
        private long remainingDelay;
        private long targetTimeMillis; /* since epoch */
        private String serial;
        private boolean executionAsked;
        private Future<?> future;

        private AtomicBoolean managed = new AtomicBoolean();

        public Task(String id, long delay, String serial) {
            if (!"".equals(id)) {
                this.id = id;
            }
            if (delay > 0) {
                remainingDelay = delay;
                targetTimeMillis = System.currentTimeMillis() + delay;
            }
            if (!"".equals(serial)) {
                this.serial = serial;
            }
        }

        @Override
        public void run() {
            if (managed.getAndSet(true)) {
                return;
            }

            try {
                CURRENT_SERIAL.set(serial);
                execute();
            } finally {
                postExecute();
            }
        }

        public abstract void execute();

        private void postExecute() {
            if (id == null && serial == null) {
                return;
            }
            CURRENT_SERIAL.set(null);
            synchronized (BackgroundExecutor.class) {
                TASKS.remove(this);

                if (serial != null) {
                    Task next = take(serial);
                    if (next != null) {
                        if (next.remainingDelay != 0) {
                            next.remainingDelay = Math.max(0L, targetTimeMillis - System.currentTimeMillis());
                        }

                        BackgroundExecutor.execute(next);
                    }
                }
            }
        }
    }
}

