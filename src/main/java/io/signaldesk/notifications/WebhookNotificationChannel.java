package io.signaldesk.notifications;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.signaldesk.events.IncidentEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;

@Component
public class WebhookNotificationChannel implements NotificationChannel {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ObjectMapper mapper;
    private final String url;
    public WebhookNotificationChannel(ObjectMapper mapper, @Value("${signaldesk.notifications.webhook-url:}") String url) {
        this.mapper = mapper; this.url = url;
    }
    public String name() { return "webhook"; }
    public void send(IncidentEvent event) throws Exception {
        if (url.isBlank()) throw new IllegalStateException("Webhook URL is not configured");
        var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(4))
                .header("Content-Type", "application/json").header("Idempotency-Key", event.eventId().toString())
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(event))).build();
        var response = client.send(request, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() < 200 || response.statusCode() >= 300)
            throw new IllegalStateException("Webhook HTTP " + response.statusCode());
    }
}
