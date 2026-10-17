package io.signaldesk.notifications;

import io.signaldesk.events.IncidentEvent;
import org.slf4j.*;
import org.springframework.stereotype.Component;

/** Fake provider for a zero-credentials demo. It does not send email or Slack messages. */
@Component
public class LogNotificationChannel implements NotificationChannel {
    private static final Logger log = LoggerFactory.getLogger(LogNotificationChannel.class);
    public String name() { return "log"; }
    public void send(IncidentEvent event) {
        log.info("notification eventId={} incidentId={} type={} recipient={}",
                event.eventId(), event.incidentId(), event.type(), event.assignee());
    }
}
