package nhcm.capitalmc.service;

import nhcm.capitalmc.CapitalMC;

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

    public static final ExecutorService CREDENTIALS = Executors.newFixedThreadPool(2,
                                                                                   Thread.ofPlatform().daemon().name("capitalmc-credentials-", 0).factory());
    public static final ExecutorService IO = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon().name("capitalmc-metadata-", 0).factory());
    public static final ExecutorService CALLBACKS = Executors.newFixedThreadPool(2,
                                                                                 Thread.ofPlatform().daemon().name("capitalmc-exchange-", 0).factory());

    public static <T> CompletableFuture<T> io(Supplier<T> work)
    {
        return CompletableFuture.supplyAsync(work, IO);
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
                    CapitalMC.logger.error("Application executor did not drain before shutdown; pending operations require recovery");
                }
            } catch (InterruptedException error)
            {
                Thread.currentThread().interrupt();
                CapitalMC.logger.error("Interrupted while draining application work", error);
                return;
            }
        }
    }
}
