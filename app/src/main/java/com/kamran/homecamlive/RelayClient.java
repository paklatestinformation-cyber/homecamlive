package com.kamran.homecamlive;

import android.util.Log;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** MQTT over WebSocket relay — one connection, all sub-topics via wildcard. */
public class RelayClient {
    private static final String TAG = "RelayClient";
    private static final String[] BROKERS = {
            "wss://broker.emqx.io:8084/mqtt",
            "wss://broker.hivemq.com:8884/mqtt"
    };

    public interface Callback {
        void onConnected(String broker);
        void onDisconnected(String reason);
        void onMessage(String topic, byte[] payload);
        void onError(String message);
    }

    private final String base;
    private final String suffix;
    private final Callback cb;
    private final ExecutorService connectExec = Executors.newSingleThreadExecutor();
    private final ExecutorService publishExec = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService retryExec = Executors.newSingleThreadScheduledExecutor();

    private volatile MqttClient client;
    private volatile boolean active = false;
    private volatile boolean wantSubscribe = false;
    private volatile String subscribeFilter = null;

    public RelayClient(String room, String suffix, Callback cb) {
        this.base = "homecamlive/" + room;
        this.suffix = suffix;
        this.cb = cb;
    }

    public String topic(String sub) { return base + "/" + sub; }

    /** subscribe=true receives frame + audio + control + status on one connection. */
    public void connect(final boolean subscribe) {
        connect(subscribe, null);
    }

    /** filter=null means all sub-topics (wildcard); otherwise one exact topic. */
    public void connect(final boolean subscribe, final String filter) {
        wantSubscribe = subscribe;
        subscribeFilter = filter;
        active = true;
        connectExec.execute(this::attemptConnect);
    }

    private void attemptConnect() {
        if (!active) return;
        String lastErr = "unknown";
        for (String broker : BROKERS) {
            try {
                String cid = "hcl-" + suffix + "-" + UUID.randomUUID().toString().substring(0, 8);
                final MqttClient c = new MqttClient(broker, cid, new MemoryPersistence());
                MqttConnectOptions opts = new MqttConnectOptions();
                opts.setCleanSession(true);
                opts.setConnectionTimeout(8);
                opts.setKeepAliveInterval(30);
                opts.setAutomaticReconnect(true);

                c.setCallback(new MqttCallbackExtended() {
                    @Override public void connectComplete(boolean reconnect, String serverURI) {
                        if (wantSubscribe) {
                            try {
                                String filter = subscribeFilter;
                                c.subscribe(filter != null ? filter : base + "/#", 0);
                            }
                            catch (Exception e) { Log.w(TAG, "subscribe failed", e); }
                        }
                    }
                    @Override public void connectionLost(Throwable cause) {
                        if (cb != null) cb.onDisconnected(cause == null ? "connection lost" : String.valueOf(cause.getMessage()));
                    }
                    @Override public void messageArrived(String t, MqttMessage m) {
                        if (cb != null) cb.onMessage(t, m.getPayload());
                    }
                    @Override public void deliveryComplete(IMqttDeliveryToken token) { }
                });

                c.connect(opts);
                client = c;
                if (cb != null) cb.onConnected(broker);
                return;
            } catch (Throwable e) {
                lastErr = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                Log.w(TAG, "broker " + broker + " failed: " + lastErr);
            }
        }
        if (cb != null) cb.onError("Relay se connect nahi ho paya: " + lastErr);
        if (active) {
            try {
                retryExec.schedule(() -> {
                    if (active) connectExec.execute(this::attemptConnect);
                }, 5, TimeUnit.SECONDS);
            } catch (Exception ignored) { }
        }
    }

    public void publish(String sub, final byte[] payload) {
        final String fullTopic = topic(sub);
        publishExec.execute(() -> {
            MqttClient c = client;
            if (c == null || !c.isConnected()) return;
            try { c.publish(fullTopic, payload, 0, false); }
            catch (Exception e) { Log.w(TAG, "publish failed: " + e.getMessage()); }
        });
    }

    public void disconnect() {
        active = false;
        retryExec.shutdownNow();
        publishExec.shutdownNow();
        connectExec.shutdownNow();
        final MqttClient c = client;
        client = null;
        if (c != null) {
            new Thread(() -> {
                try { c.disconnect(); c.close(); } catch (Exception ignored) { }
            }, "relay-close").start();
        }
    }
}
