package io.signaldesk.notifications;
import io.signaldesk.events.IncidentEvent;

public interface NotificationChannel {
    String name();
    void send(IncidentEvent event) throws Exception;
}
