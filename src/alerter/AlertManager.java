package alerter;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Stores active alerts to avoid duplicates.
 */
public final class AlertManager {

    /** Types of alerts we can raise */
    public enum AlertType { OVER, UNDER, OLD_VALUE, ZABBIX_DOWN }

    /** key = itemId, value = current alert type */
    private static final ConcurrentMap<Long, AlertType> alerts = new ConcurrentHashMap<>();

    /** Special key (0L) used for global ZABBIX_DOWN alert */
    private static final Long GLOBAL = 0L;

    private AlertManager() {}

    public static boolean wasAlerted(long itemId, AlertType type) {
        return alerts.get(itemId) == type;
    }
    public static void setAlert(long itemId, AlertType type) {
        alerts.put(itemId, type);
    }
    public static void clearAlert(long itemId) {
        alerts.remove(itemId);
    }
    public static AlertType getAlert(long itemId) {
        return alerts.get(itemId);
    }

    /* Global Zabbix-down helpers */
    public static boolean isGlobalDown() { return wasAlerted(GLOBAL, AlertType.ZABBIX_DOWN); }
    public static void setGlobalDown()  { setAlert(GLOBAL,  AlertType.ZABBIX_DOWN); }
    public static void clearGlobalDown(){ clearAlert(GLOBAL); }
}
