package zabbix;

import config.Secrets;
import config.Metric;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import com.fasterxml.jackson.databind.*;

public class ZabbixClient {
    private static final ObjectMapper mapper = new ObjectMapper();

    private final String url;
    private final String apiToken;

    public ZabbixClient(String url, String apiToken) {
        this.url = url;
        this.apiToken = apiToken;
    }

    // Получить itemid по host+key
    public Long resolveItemId(String host, String key) throws IOException {
        String reqId = UUID.randomUUID().toString();
        Map<String, Object> params = new HashMap<>();
        params.put("output", new String[] { "itemid" });
        params.put("filter", Map.of("host", host));  // исправили!
        params.put("search", Map.of("key_", key));
        params.put("limit", 1);

        Map<String, Object> request = new HashMap<>();
        request.put("jsonrpc", "2.0");
        request.put("method", "item.get");
        request.put("params", params);
        request.put("auth", apiToken);
        request.put("id", reqId);

        String json = mapper.writeValueAsString(request);
        String response = doPost(json);
        JsonNode resp = mapper.readTree(response);

        if (resp.has("result") && resp.get("result").isArray() && resp.get("result").size() > 0) {
            return resp.get("result").get(0).get("itemid").asLong();
        }
        return null;
    }



    // Returns the latest value and clock for a metric
    public alerter.MetricPoller.ZabbixValue getLastValueAndClock(Long itemId) throws IOException {
        alerter.MetricPoller.ZabbixValue val = getHistoryValue(itemId, 0); // сначала пробуем float
        if (val == null) {
            val = getHistoryValue(itemId, 3); // если нет, пробуем integer
        }
        return val;
    }

    private alerter.MetricPoller.ZabbixValue getHistoryValue(Long itemId, int historyType) throws IOException {
        String reqId = java.util.UUID.randomUUID().toString();
        Map<String, Object> params = new HashMap<>();
        params.put("output", "extend");
        params.put("history", historyType); // тип данных
        params.put("itemids", Collections.singletonList(itemId));
        params.put("sortfield", "clock");
        params.put("sortorder", "DESC");
        params.put("limit", 1);

        Map<String, Object> request = new HashMap<>();
        request.put("jsonrpc", "2.0");
        request.put("method", "history.get");
        request.put("params", params);
        request.put("auth", apiToken);
        request.put("id", reqId);

        String json = mapper.writeValueAsString(request);
        String response = doPost(json);
        JsonNode resp = mapper.readTree(response);

        if (resp.has("result") && resp.get("result").isArray() && resp.get("result").size() > 0) {
            JsonNode rec = resp.get("result").get(0);
            double value = Double.parseDouble(rec.get("value").asText().replace(",", "."));
            long clock = rec.get("clock").asLong();
            return new alerter.MetricPoller.ZabbixValue(value, clock);
        }
        return null;
    }


    private String doPost(String json) throws IOException {
        URL u = new URL(url);
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setConnectTimeout(5000);
        c.setReadTimeout(10000);

        try (OutputStream os = c.getOutputStream()) {
            os.write(json.getBytes(StandardCharsets.UTF_8));
        }

        try (InputStream is = c.getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
