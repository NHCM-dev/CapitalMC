package nhcm.capitalmc.service;

import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * CPU credentials and blocking metadata IO never run on the server or native callback threads.
 */
public class ApplicationExecutors
{
    private ApplicationExecutors()
    {
    }

    public static final ExecutorService CREDENTIALS = bounded("capitalmc-credentials-", 2, 256);
    public static final ExecutorService IO = bounded("capitalmc-metadata-", 1, 4096);
    public static final ExecutorService CALLBACKS = bounded("capitalmc-exchange-", 2, 4096);

    private static ExecutorService bounded(String name, int threads, int capacity)
    {
        return new ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(capacity),
                                      Thread.ofPlatform().daemon().name(name, 0).factory(), new ThreadPoolExecutor.AbortPolicy());
    }

    public static <T> CompletableFuture<T> io(Supplier<T> work)
    {
        try
        {
            return CompletableFuture.supplyAsync(work, IO);
        }
        catch (RejectedExecutionException failure)
        {
            return CompletableFuture.failedFuture(failure);
        }
    }

    public static void shutdown()
    {
        for (ExecutorService executor : new ExecutorService[]{CREDENTIALS, CALLBACKS, IO})
        {
            executor.shutdown();
            try
            {
                if (!executor.awaitTermination(10, TimeUnit.SECONDS))
                {
                    System.getLogger(ApplicationExecutors.class.getName()).log(System.Logger.Level.ERROR,
                            "Application executor did not drain before shutdown; pending operations require recovery");
                }
            } catch (InterruptedException error)
            {
                Thread.currentThread().interrupt();
                System.getLogger(ApplicationExecutors.class.getName()).log(System.Logger.Level.ERROR, "Interrupted while draining application work", error);
                return;
            }
        }
    }
}
