package ru.mail.polis.service;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import one.nio.http.HttpClient;
import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Param;
import one.nio.http.Path;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.net.ConnectionString;
import one.nio.net.Socket;
import one.nio.server.AcceptorConfig;
import one.nio.server.RejectedSessionException;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.mail.polis.Record;
import ru.mail.polis.dao.DAO;
import ru.mail.polis.util.Util;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Map.entry;

public class ReplicationServiceImpl extends HttpServer implements Service {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReplicationServiceImpl.class);
    static final String FORWARD_REQUEST_HEADER = "X-OK-Proxy: True";
    static final String GATEWAY_TIMEOUT_ERROR_LOG = "Your request failed due to timeout";
    private static final int CONNECTION_TIMEOUT = 1000;
    private final DAO dao;

    private enum ErrorNames {
        IO_ERROR, NOT_ALLOWED_METHOD_ERROR
    }

    private static final Map<ErrorNames, String> MESSAGE_MAP = Map.ofEntries(
            entry(ErrorNames.IO_ERROR, "IO exception raised"),
            entry(ErrorNames.NOT_ALLOWED_METHOD_ERROR, "Method not allowed")
    );

    private final ExecutorService exec;
    private final Topology topology;
    private final Map<String, HttpClient> nodesToClients;
    private final ReplicationFactor rf;
    private final ReplicationHandler handler;

    ReplicationServiceImpl(
            final int port,
            @NotNull final DAO dao,
            final int workerPoolSize,
            final int queueSize,
            @NotNull final Topology topology
    ) throws IOException {

        super(getConfig(port));
        this.exec = new ThreadPoolExecutor(workerPoolSize, workerPoolSize,
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueSize),
                new ThreadFactoryBuilder()
                        .setNameFormat("worker-%d")
                        .setUncaughtExceptionHandler((t, e) -> LOGGER.error("Worker {} fails running: {}", t, e))
                        .build(),
                new ThreadPoolExecutor.AbortPolicy()
        );
        this.topology = topology;
        this.nodesToClients = new HashMap<>();
        this.dao = dao;
        this.rf = new ReplicationFactor(topology.getSize() / 2 + 1, topology.getSize());
        this.handler = new ReplicationHandler(dao, topology, nodesToClients, rf);

        for (final String node : topology.getNodes()) {

            if (topology.isSelfId(node)) {
                continue;
            }

            final HttpClient client = new HttpClient(new ConnectionString(node + "?timeout=" + CONNECTION_TIMEOUT));
            if (nodesToClients.put(node, client) != null) {
                throw new IllegalStateException("Found multiple nodes with same ID");
            }
        }
    }

    /**
     * handles formation request to inform client the server is alive and ready to exchange.
     *
     * @param session - ongoing session instance
     */
    @Path("/v0/status")
    public void status(@NotNull final HttpSession session) throws IOException {
        session.sendResponse(Response.ok("Server is running..."));
    }

    @Override
    public HttpSession createSession(final Socket socket) throws RejectedSessionException {
        return new StreamSession(socket, this);
    }

    @Path("/v0/entities")
    public void entities(@Param(value = "start", required = true) final String idStart,
                         @Param(value = "end") final String idEnd,
                         final HttpSession session) {
        try {
            if (idStart.isEmpty() || ((idEnd != null) && idEnd.isEmpty())) {
                throw new IllegalArgumentException();
            }

            final ByteBuffer start = ByteBuffer.wrap(idStart.getBytes(UTF_8));
            final ByteBuffer end = (idEnd == null) ? null
                    : ByteBuffer.wrap(idEnd.getBytes(UTF_8));
            final Iterator<Record> iterator = dao.range(start,end);
            ((StreamSession) session).setIterator(iterator);
        } catch (IOException ex) {
            // log.error("Can not send entities", ex);
        } catch (IllegalArgumentException ex) {
            // Util.sendResponse(session,
                    // new Response(Response.BAD_REQUEST, Response.EMPTY));
        }
    }

    /**
     * resolves request handling by HTTP REST methods, provides any client with response (incl. server outcome code).
     *
     * @param id      - String object to be processed as a key in terms of data storage design
     * @param req     - HTTP request
     * @param session - ongoing session instance
     */
    @Path("/v0/entity")
    public void entity(
            @Param(value = "id", required = true) final String id,
            @NotNull final Request req,
            @NotNull final HttpSession session,
            @Param("replicas") final String replicas
    ) throws IOException {

        if (id.isEmpty()) {
            session.sendError(Response.BAD_REQUEST, "Identifier is required as parameter. Error handling request");
            return;
        }

        final boolean isForwardedRequest = req.getHeader(FORWARD_REQUEST_HEADER) != null;

        final ByteBuffer byteBuffer = ByteBuffer.wrap(id.getBytes(StandardCharsets.UTF_8));
        final ReplicationFactor replicationFactor = ReplicationFactor
                .getReplicationFactor(replicas, rf, session);

        if (topology.getSize() > 1) {
            try {
                handleMultipleCase(req, session, id, replicationFactor, isForwardedRequest);
            } catch (IOException exc) {
                session.sendError(Response.GATEWAY_TIMEOUT, GATEWAY_TIMEOUT_ERROR_LOG);
            }
        } else {
            try {
                handleSingleCase(req, session, byteBuffer);
            } catch (IOException exc) {
                session.sendError(Response.GATEWAY_TIMEOUT, GATEWAY_TIMEOUT_ERROR_LOG);
            }
        }
    }

    private void handleSingleCase(
            final Request request,
            final HttpSession session,
            final ByteBuffer key
    ) throws IOException {
        switch (request.getMethod()) {
            case Request.METHOD_GET:
                runExecutor(session, () -> handler.singleGet(key, request));
                break;
            case Request.METHOD_PUT:
                runExecutor(session, () -> handler.singleUpsert(key, request.getBody(), request));
                break;
            case Request.METHOD_DELETE:
                runExecutor(session, () -> handler.singleDelete(key, request));
                break;
            default:
                session.sendError(Response.METHOD_NOT_ALLOWED, MESSAGE_MAP.get(ErrorNames.NOT_ALLOWED_METHOD_ERROR));
                break;
        }
    }

    private void handleMultipleCase(
            final Request request,
            final HttpSession session,
            final String id,
            final ReplicationFactor replicationFactor,
            final boolean isForwardedRequest
    ) throws IOException {
        switch (request.getMethod()) {
            case Request.METHOD_GET:
                session.sendResponse(
                        handler.multipleGet(
                                id,
                                replicationFactor,
                                isForwardedRequest
                        )
                );
                break;
            case Request.METHOD_PUT:
                session.sendResponse(handler.multipleUpsert(
                        id,
                        request.getBody(),
                        replicationFactor.getAck(),
                        isForwardedRequest)
                );
                break;
            case Request.METHOD_DELETE:
                session.sendResponse(
                        handler.multipleDelete(
                                id,
                                replicationFactor.getAck(),
                                isForwardedRequest
                        )
                );
                break;
            default:
                session.sendError(Response.METHOD_NOT_ALLOWED, MESSAGE_MAP.get(ErrorNames.NOT_ALLOWED_METHOD_ERROR));
                break;
        }
    }

    @Override
    public void handleDefault(@NotNull final Request req, @NotNull final HttpSession session) throws IOException {
        session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
    }

    private void runExecutor(@NotNull final HttpSession session, final Runner runner) {
        exec.execute(() -> {
            try {
                session.sendResponse(runner.execute());
            } catch (IOException exc) {
                LOGGER.error(MESSAGE_MAP.get(ErrorNames.IO_ERROR));
            }
        });
    }

    @Override
    public synchronized void stop() {
        super.stop();
        exec.shutdown();
        try {
            exec.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            LOGGER.error("Couldn't terminate executor");
            Thread.currentThread().interrupt();
        }
        for (final HttpClient client : nodesToClients.values()) {
            client.close();
        }
    }

    private static HttpServerConfig getConfig(final int port) {
        if (port <= 1024 || port >= 65536) {
            throw new IllegalArgumentException("Invalid port");
        }
        final AcceptorConfig acc = new AcceptorConfig();
        final HttpServerConfig config = new HttpServerConfig();
        acc.port = port;
        acc.deferAccept = true;
        acc.reusePort = true;
        config.acceptors = new AcceptorConfig[]{acc};
        return config;
    }
}
