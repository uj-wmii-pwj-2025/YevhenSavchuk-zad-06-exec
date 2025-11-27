package uj.wmii.pwj.exec;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class MyExecService implements ExecutorService {

    static MyExecService newInstance() {
        return new MyExecService();
    }

    private final BlockingQueue<Runnable> taskQueue = new LinkedBlockingQueue<>();

    private final Thread workerThread;

    private volatile boolean running = true; // true while accepting tasks
    private volatile boolean shutdownRequested = false;
    private final AtomicBoolean workerAlive = new AtomicBoolean(true);
    private final CountDownLatch terminationSignal = new CountDownLatch(1);

    private MyExecService() {
        workerThread = new Thread(this::workerLoop, "MyExecService-Worker");
        workerThread.start();
    }

    private void workerLoop() {
        try {
            while (running || !taskQueue.isEmpty()) {
                Runnable task;
                try {
                    task = taskQueue.poll(100, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    if (shutdownRequested) break;
                    continue;
                }
                if (task != null) {
                    try {
                        task.run();
                    } catch (Throwable ignored) {
                        continue;
                    }
                }
            }
        } finally {
            workerAlive.set(false);
            terminationSignal.countDown();
        }
    }

    private void enqueue(Runnable task) {
        if (!running) throw new RejectedExecutionException("Executor has been shut down");
        taskQueue.offer(task);
    }

    @Override
    public void shutdown() {
        running = false;
    }

    @Override
    public List<Runnable> shutdownNow() {
        running = false;
        shutdownRequested = true;
        workerThread.interrupt();
        List<Runnable> remaining = new ArrayList<>();
        taskQueue.drainTo(remaining);
        return remaining;
    }

    @Override
    public boolean isShutdown() {
        return !running;
    }

    @Override
    public boolean isTerminated() {
        return !workerAlive.get() && taskQueue.isEmpty();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        shutdown();
        workerThread.join(unit.toMillis(timeout));
        return isTerminated();
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        Objects.requireNonNull(task);
        FutureTask<T> future = new FutureTask<>(task);
        enqueue(future);
        return future;
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        Objects.requireNonNull(task);
        FutureTask<T> future = new FutureTask<>(task, result);
        enqueue(future);
        return future;
    }

    @Override
    public Future<?> submit(Runnable task) {
        return submit(task, null);
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
        return invokeAll(tasks, Long.MAX_VALUE, TimeUnit.DAYS);
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(tasks);
        Objects.requireNonNull(unit);

        List<Future<T>> futures = new ArrayList<>();
        for (Callable<T> task : tasks) {
            futures.add(submit(task));
        }

        long endTime = System.nanoTime() + unit.toNanos(timeout);

        for (Future<T> f : futures) {
            long remaining = endTime - System.nanoTime();
            if (remaining <= 0) {
                f.cancel(true);
                continue;
            }
            try {
                f.get(remaining, TimeUnit.NANOSECONDS);
            } catch (ExecutionException | TimeoutException ignored) {
                f.cancel(true);
            }
        }
        return futures;
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
        try {
            return invokeAny(tasks, Long.MAX_VALUE, TimeUnit.DAYS);
        } catch (TimeoutException e) {
            throw new ExecutionException(e);
        }
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        Objects.requireNonNull(tasks);
        Objects.requireNonNull(unit);
        if (tasks.isEmpty()) throw new IllegalArgumentException("No tasks provided");

        List<Future<T>> futures = new ArrayList<>();
        for (Callable<T> task : tasks) futures.add(submit(task));

        long deadline = System.nanoTime() + unit.toNanos(timeout);

        while (true) {
            for (Future<T> f : futures) {
                if (f.isDone()) {
                    for (Future<T> other : futures) if (other != f) other.cancel(true);
                    try {
                        return f.get();
                    } catch (ExecutionException e) {
                        continue;
                    }
                }
            }
            if (System.nanoTime() > deadline) {
                for (Future<T> f : futures) f.cancel(true);
                throw new TimeoutException("invokeAny timed out");
            }
            Thread.sleep(1);
        }
    }

    @Override
    public void execute(Runnable command) {
        Objects.requireNonNull(command);
        enqueue(command);
    }
}
