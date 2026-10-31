package dev.webinfra.labs.orders;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * An append-only log of order events, and the open Server-Sent Events streams that follow it.
 * Every event has an increasing id, so a client that reconnects with Last-Event-ID misses nothing.
 */
@Component
public class OrderEvents implements SmartLifecycle {
  private volatile boolean running;
  public record Event(long id, String type, Map<String, Object> data) {}

  private final List<Event> log = new ArrayList<>();
  private final List<SseEmitter> streams = new CopyOnWriteArrayList<>();
  private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor();

  public OrderEvents(@Value("${events.heartbeat-ms}") long heartbeatMs) {
    heartbeat.scheduleAtFixedRate(() -> {
      for (SseEmitter s : streams) {
        try {
          s.send(SseEmitter.event().comment("keep-alive"));   // ": keep-alive" — ignored by clients
        } catch (IOException e) {
          streams.remove(s);
        }
      }
    }, heartbeatMs, heartbeatMs, TimeUnit.MILLISECONDS);
  }

  /**
   * On shutdown, end every open stream first. Graceful shutdown waits for in-flight requests,
   * and a stream never finishes by itself, so without this the server would hang until it times out.
   * Phase MAX_VALUE stops before the web server's graceful-shutdown phase.
   */
  @Override
  public void stop() {
    running = false;
    heartbeat.shutdownNow();
    streams.forEach(SseEmitter::complete);
    streams.clear();
  }

  @Override
  public void start() {
    running = true;
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  @Override
  public int getPhase() {
    return Integer.MAX_VALUE;
  }

  public synchronized Event publish(String type, Map<String, Object> data) {
    Event e = new Event(log.size() + 1, type, data);
    log.add(e);
    for (SseEmitter s : streams) send(s, e);
    return e;
  }

  /** Opens a stream: first replays everything after lastEventId, then follows live events. */
  public synchronized SseEmitter subscribe(long lastEventId) {
    SseEmitter emitter = new SseEmitter(0L);   // no server-side timeout
    for (Event e : log) if (e.id() > lastEventId) send(emitter, e);
    streams.add(emitter);
    emitter.onCompletion(() -> streams.remove(emitter));
    emitter.onError(t -> streams.remove(emitter));
    return emitter;
  }

  private void send(SseEmitter s, Event e) {
    try {
      s.send(SseEmitter.event().id(String.valueOf(e.id())).name(e.type()).data(e.data()).reconnectTime(3000));
    } catch (IOException ex) {
      streams.remove(s);
    }
  }

  static Map<String, Object> created(String id, String customer, long total) {
    return Map.of("id", id, "customer", customer, "total", total, "at", Instant.now().toString());
  }
}
