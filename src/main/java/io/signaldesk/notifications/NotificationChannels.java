package io.signaldesk.notifications;

import org.springframework.stereotype.Component;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class NotificationChannels {
    private final Map<String, NotificationChannel> channels;
    public NotificationChannels(List<NotificationChannel> channels) {
        this.channels = channels.stream().collect(Collectors.toMap(NotificationChannel::name, c -> c));
    }
    public NotificationChannel get(String name) {
        var channel = channels.get(name);
        if (channel == null) throw new IllegalArgumentException("Unknown notification channel");
        return channel;
    }
}
