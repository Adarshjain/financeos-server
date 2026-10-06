package com.financeos.core.warmup;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.financeos.core.observability.Events;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ClientWarmPingerTest {

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private ListAppender<ILoggingEvent> logs;
    private Logger pingerLog;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/login", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath()
                    + " ua=" + exchange.getRequestHeaders().getFirst("User-Agent")
                    + " cookie=" + exchange.getRequestHeaders().getFirst("Cookie"));
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.createContext("/hang", exchange -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();

        pingerLog = (Logger) LoggerFactory.getLogger(ClientWarmPinger.class);
        logs = new ListAppender<>();
        logs.start();
        pingerLog.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        pingerLog.detachAppender(logs);
        server.stop(0);
    }

    private ClientWarmPinger pinger(String path, long timeoutMs) {
        return new ClientWarmPinger(HttpClient.newHttpClient(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path), Duration.ofMillis(timeoutMs));
    }

    private List<ILoggingEvent> events(String event) {
        return logs.list.stream()
                .filter(e -> e.getArgumentArray() != null && List.of(e.getArgumentArray()).stream()
                        .anyMatch(a -> String.valueOf(a).equals("event=" + event)))
                .toList();
    }

    @Test
    void getsTheTargetWithItsUserAgentAndNoSessionCookie() throws Exception {
        boolean ok = pinger("/login", 5_000).send().get(5, TimeUnit.SECONDS);

        assertThat(ok).isTrue();
        assertThat(requests).containsExactly("GET /login ua=" + ClientWarmPinger.USER_AGENT + " cookie=null");
    }

    @Test
    void errorStatusCountsAsFailure() throws Exception {
        status = 500;

        assertThat(pinger("/login", 5_000).send().get(5, TimeUnit.SECONDS)).isFalse();
    }

    @Test
    void unreachableTargetFailsWithoutThrowing() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        ClientWarmPinger pinger = new ClientWarmPinger(HttpClient.newHttpClient(),
                URI.create("http://127.0.0.1:" + closedPort + "/login"), Duration.ofSeconds(2));

        assertThat(pinger.send().get(5, TimeUnit.SECONDS)).isFalse();
    }

    @Test
    void slowTargetIsAbandonedAtTheTimeout() throws Exception {
        long start = System.nanoTime();

        boolean ok = pinger("/hang", 300).send().get(5, TimeUnit.SECONDS);

        assertThat(ok).isFalse();
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(3_000);
    }

    /** The scheduler thread is shared with the job poller — a ping must never hold it. */
    @Test
    void scheduledTickReturnsWithoutWaitingForTheResponse() throws Exception {
        long start = System.nanoTime();

        pinger("/hang", 1_500).ping();

        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(1_000);
        // The outcome is still recorded once the async request settles (here: the timeout).
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (events(Events.CLIENT_WARM_PING_FAILED).isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(events(Events.CLIENT_WARM_PING_FAILED)).singleElement()
                .satisfies(e -> assertThat(e.getFormattedMessage()).containsPattern("reason=(Http)?TimeoutException"));
    }

    @Test
    void logsAnOutageOnceAndItsRecoveryOnce() throws Exception {
        ClientWarmPinger pinger = pinger("/login", 5_000);

        status = 500;
        pinger.send().get(5, TimeUnit.SECONDS);
        pinger.send().get(5, TimeUnit.SECONDS);
        status = 200;
        pinger.send().get(5, TimeUnit.SECONDS);
        pinger.send().get(5, TimeUnit.SECONDS);

        List<ILoggingEvent> failed = events(Events.CLIENT_WARM_PING_FAILED);
        List<ILoggingEvent> recovered = events(Events.CLIENT_WARM_PING_RECOVERED);
        assertThat(failed).hasSize(1);
        assertThat(failed.get(0).getLevel()).isEqualTo(Level.WARN);
        assertThat(recovered).hasSize(1);
        assertThat(recovered.get(0).getLevel()).isEqualTo(Level.INFO);
    }

    @Test
    void healthyPingsLogNothingAboveDebug() throws Exception {
        ClientWarmPinger pinger = pinger("/login", 5_000);

        pinger.send().get(5, TimeUnit.SECONDS);
        pinger.send().get(5, TimeUnit.SECONDS);

        assertThat(logs.list).allMatch(e -> e.getLevel().toInt() <= Level.DEBUG.toInt());
    }

    @Test
    void beanExistsOnlyWhenEnabled() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(ClientWarmPinger.class)
                .withPropertyValues("warm-ping.url=http://localhost/login");

        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(ClientWarmPinger.class));
        runner.withPropertyValues("warm-ping.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(ClientWarmPinger.class));
        runner.withPropertyValues("warm-ping.enabled=true")
                .run(ctx -> assertThat(ctx).hasSingleBean(ClientWarmPinger.class));
    }

    @Test
    void offByDefaultOnInProdAndTargetsTheUiLoginPage() throws IOException {
        StandardEnvironment base = environment(false);
        StandardEnvironment prod = environment(true);

        assertThat(base.getProperty("warm-ping.enabled", Boolean.class)).isFalse();
        assertThat(prod.getProperty("warm-ping.enabled", Boolean.class)).isTrue();
        assertThat(prod.getProperty("warm-ping.url"))
                .isEqualTo(prod.getProperty("app.ui-path") + "/login");
    }

    private static StandardEnvironment environment(boolean withProd) throws IOException {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        StandardEnvironment env = new StandardEnvironment();
        if (withProd) {
            loader.load("prod", new ClassPathResource("application-prod.yml"))
                    .forEach(ps -> env.getPropertySources().addLast(ps));
        }
        loader.load("base", new ClassPathResource("application.yml"))
                .forEach(ps -> env.getPropertySources().addLast(ps));
        return env;
    }
}
