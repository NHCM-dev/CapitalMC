package nhcm.capitalmc.backend;

import com.google.gson.JsonObject;
import nhcm.capitalmc.management.ServerUserManager;
import nhcm.capitalmc.service.ApplicationExecutors;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/** Standalone Java 25 server; no Fabric, Minecraft, world or SGui is loaded. */
public class ExchangeServerMain
{
    public static void main(String[] args) throws Exception
    {
        Path directory = Path.of(args.length == 0 ? "capitalmc" : args[0]).toAbsolutePath();
        var config = NetworkConfig.load(directory.resolve("connection.json"));
        if (config.mode != NetworkConfig.Mode.LOCAL) throw new IllegalArgumentException("Standalone owner must use LOCAL mode");
        config.listen = true;
        var users = new ServerUserManager(directory.resolve("users.dat"));
        var runtime = new ExchangeRuntime(config, directory, users);
        try
        {
            runtime.ready.join();
        }
        catch (Throwable failure)
        {
            runtime.close();
            throw failure;
        }
        var stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            runtime.close();
            ApplicationExecutors.shutdown();
            stopped.countDown();
        }, "capitalmc-backend-stop"));
        System.out.println("CapitalMC exchange ready: " + config.bind + ":" + config.port);
        System.out.println("Exchange ID: " + runtime.backend.call(BackendClient.INFO, new JsonObject()).join()
                                                               .getAsJsonObject().get("exchangeId").getAsString());
        stopped.await();
    }
}
