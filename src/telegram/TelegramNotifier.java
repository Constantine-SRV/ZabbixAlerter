package telegram;

import config.Secrets;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/**
 * TelegramNotifier
 * ---------------
 * Simple class for sending messages to a Telegram group or user using the Bot API.
 */
public class TelegramNotifier {
    private final String botToken;
    private final String chatId;

    public TelegramNotifier(String botToken, String chatId) {
        this.botToken = botToken;
        this.chatId = chatId;
    }

    /**
     * Sends a text message to the configured chat.
     * @param message The text message to send.
     * @throws IOException If HTTP request fails.
     */
    public void sendMessage(String message) throws IOException {
        String urlString = String.format(
                "https://api.telegram.org/bot%s/sendMessage",
                botToken
        );
        String data = String.format("chat_id=%s&text=%s",
                URLEncoder.encode(chatId, StandardCharsets.UTF_8),
                URLEncoder.encode(message, StandardCharsets.UTF_8)
        );

        byte[] postData = data.getBytes(StandardCharsets.UTF_8);
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setDoOutput(true);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setRequestProperty("Charset", "utf-8");
        conn.setRequestProperty("Content-Length", Integer.toString(postData.length));

        try (OutputStream os = conn.getOutputStream()) {
            os.write(postData);
        }

        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            String err = new String(conn.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            throw new IOException("Telegram API error: " + responseCode + " - " + err);
        }
    }
}
