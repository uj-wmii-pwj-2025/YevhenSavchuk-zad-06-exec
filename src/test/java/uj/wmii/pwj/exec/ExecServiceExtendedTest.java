package uj.wmii.pwj.exec;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

public class ExecServiceExtendedTest {

    @Test
    void testIsShutdownAndIsTerminated() throws Exception {
        MyExecService s = MyExecService.newInstance();
        assertFalse(s.isShutdown());
        assertFalse(s.isTerminated());
        s.shutdown();
        assertTrue(s.isShutdown());
        s.awaitTermination(100, TimeUnit.MILLISECONDS);
        assertTrue(s.isTerminated());
    }

    @Test
    void testShutdownNowReturnsPendingTasks() throws Exception {
        MyExecService s = MyExecService.newInstance();
        TestRunnable r1 = new TestRunnable();
        TestRunnable r2 = new TestRunnable();
        s.execute(r1);
        s.execute(r2);
        List<Runnable> pending = s.shutdownNow();

        assertTrue(pending.contains(r2) || pending.contains(r1));
    }

    @Test
    void testAwaitTerminationTimeout() throws Exception {
        MyExecService s = MyExecService.newInstance();
        Future<String> f = s.submit(() -> {
            Thread.sleep(50);
            return "ok";
        });
        s.shutdown();
        boolean finished = s.awaitTermination(10, TimeUnit.MILLISECONDS);
        assertFalse(finished);
        f.get();
        s.awaitTermination(100, TimeUnit.MILLISECONDS);
        assertTrue(s.isTerminated());
    }

    @Test
    void testInvokeAllCompletesAllTasks() throws Exception {
        MyExecService s = MyExecService.newInstance();
        List<Callable<Integer>> tasks = Arrays.asList(
                () -> { Thread.sleep(10); return 1; },
                () -> { Thread.sleep(20); return 2; },
                () -> { Thread.sleep(5); return 3; }
        );
        List<Future<Integer>> futures = s.invokeAll(tasks);
        assertEquals(3, futures.size());
        int sum = 0;
        for (Future<Integer> f : futures) sum += f.get();
        assertEquals(6, sum);
    }

    @Test
    void testInvokeAllWithTimeoutCancelsLongTasks() throws Exception {
        MyExecService s = MyExecService.newInstance();
        List<Callable<Integer>> tasks = Arrays.asList(
                () -> { Thread.sleep(100); return 1; },
                () -> { Thread.sleep(50); return 2; }
        );
        List<Future<Integer>> futures = s.invokeAll(tasks, 20, TimeUnit.MILLISECONDS);
        for (Future<Integer> f : futures) {
            if (!f.isDone()) f.cancel(true);
        }
        assertTrue(futures.stream().anyMatch(Future::isCancelled));
    }

    @Test
    void testInvokeAnyWithTimeoutThrows() {
        MyExecService s = MyExecService.newInstance();
        List<Callable<String>> tasks = Arrays.asList(
                () -> { Thread.sleep(50); return "A"; },
                () -> { Thread.sleep(100); return "B"; }
        );
        assertThrows(TimeoutException.class, () -> s.invokeAny(tasks, 10, TimeUnit.MILLISECONDS));
    }

    @Test
    void testTaskExceptionHandling() throws Exception {
        MyExecService s = MyExecService.newInstance();
        Future<Integer> f = s.submit(() -> { throw new IllegalStateException("fail"); });
        assertThrows(ExecutionException.class, f::get);
    }

    @Test
    void testSubmitAfterShutdownThrows() {
        MyExecService s = MyExecService.newInstance();
        s.shutdown();
        assertThrows(RejectedExecutionException.class, () -> s.submit(() -> 1));
    }
}
