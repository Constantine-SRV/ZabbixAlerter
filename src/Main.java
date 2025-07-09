import config.*;
import zabbix.ZabbixClient;
import alerter.MetricPoller;
import telegram.TelegramNotifier;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Main entry point for ZabbixAlerter.
 * Command-line argument 0 (optional) = status-print interval in minutes.
 */
public class Main {
    public static void main(String[] args) throws Exception {

        /* 1. Parse command-line argument */
        int statusIntervalMin = 1;                    // default = 1 min
        if (args.length > 0) {
            try {
                int tmp = Integer.parseInt(args[0]);
                if (tmp > 0) statusIntervalMin = tmp; // only positive values
                System.out.println("Using interval: " + statusIntervalMin +" min");
            } catch (NumberFormatException ignored) {
                System.err.println("Invalid interval '" + args[0] + "', using default 1 min.");
            }
        }
        System.out.printf("Status print interval: %d min%n", statusIntervalMin);

        /* 2. Load config, resolve itemIds */
        MetricsConfig cfg = ConfigLoader.load();
        ZabbixClient client = new ZabbixClient(Secrets.ZABBIX_URL(), Secrets.ZABBIX_API_TOKEN());

        ExecutorService ioPool = Executors.newFixedThreadPool(10);
        for (Metric m : cfg.getMetricList()) {
            ioPool.submit(() -> {
                try {
                    Long id = client.resolveItemId(m.getHost(), m.getKey());
                    m.setMetricId(id);
                    System.out.printf("Host=%s Key=%s ⇒ itemId=%s%n",
                            m.getHost(), m.getKey(), id != null ? id : "NOT FOUND");
                } catch (Exception e) {
                    System.out.printf("Error resolving itemId for %s %s: %s%n",
                            m.getHost(), m.getKey(), e.getMessage());
                }
            });
        }
        ioPool.shutdown();
        ioPool.awaitTermination(30, TimeUnit.SECONDS);

        /* 3. Init Telegram notifier */
        TelegramNotifier notifier = new TelegramNotifier(
                Secrets.TELEGRAM_BOT_TOKEN(),
                Secrets.TELEGRAM_CHAT_ID()
        );

        new Thread(() -> {
            try {
                notifier.sendMessage("ZabbixAlerter started V-0.1.12");
            } catch (Exception e) {
                System.err.println("Failed to send startup message: " + e.getMessage());
            }
        }).start();

        /* 4. Start metric polling: 5 threads, poll 60 s, status interval from CLI */
        MetricPoller poller = new MetricPoller(
                cfg.getMetricList(),
                client,
                notifier,
                5,          // thread pool size
                60,         // poll period (seconds)
                statusIntervalMin
        );
        poller.start();

        /* 5. Keep JVM alive */
        Thread.currentThread().join();
    }
}
