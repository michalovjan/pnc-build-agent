package org.jboss.pnc.buildagent.server.servlet;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.termd.core.pty.PtyMaster;
import io.termd.core.pty.Status;
import org.jboss.pnc.buildagent.api.TaskStatusUpdateEvent;
import org.jboss.pnc.buildagent.api.httpinvoke.Request;
import org.jboss.pnc.buildagent.api.httpinvoke.Cancel;
import org.jboss.pnc.buildagent.api.httpinvoke.InvokeRequest;
import org.jboss.pnc.buildagent.api.httpinvoke.InvokeResponse;
import org.jboss.pnc.buildagent.api.httpinvoke.RetryConfig;
import org.jboss.pnc.buildagent.common.Arrays;
import org.jboss.pnc.buildagent.common.http.HttpClient;
import org.jboss.pnc.buildagent.common.security.Md5;
import org.jboss.pnc.buildagent.server.ReadOnlyChannel;
import org.jboss.pnc.buildagent.server.httpinvoker.CommandSession;
import org.jboss.pnc.buildagent.server.httpinvoker.SessionRegistry;
import org.jboss.pnc.buildagent.server.termserver.StatusConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * @author <a href="mailto:matejonnet@gmail.com">Matej Lazar</a>
 */
public class HttpInvoker extends HttpServlet {

    private final Logger logger = LoggerFactory.getLogger(HttpInvoker.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Set<ReadOnlyChannel> readOnlyChannels;

    private final SessionRegistry sessionRegistry;

    private final HttpClient httpClient;

    private final RetryConfig retryConfig;

    private final Md5 stdoutChecksum;


    public HttpInvoker(
            Set<ReadOnlyChannel> readOnlyChannels,
            SessionRegistry sessionRegistry,
            HttpClient httpClient,
            RetryConfig retryConfig)
            throws NoSuchAlgorithmException {
        this.readOnlyChannels = readOnlyChannels;
        this.sessionRegistry = sessionRegistry;
        this.httpClient = httpClient;
        this.retryConfig = retryConfig;
        this.stdoutChecksum = new Md5();
    }

    @Override
    protected void doPut(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        Cancel cancelRequest = objectMapper.readValue(request.getInputStream(), Cancel.class);
        Optional<CommandSession> commandSession = sessionRegistry.get(cancelRequest.getSessionId());

        if (commandSession.isPresent()) {
            PtyMaster ptyMaster = commandSession.get().getPtyMaster();
            ptyMaster.interruptProcess(); //onComplete is called when the process is interrupted
            response.setStatus(200);
        } else {
            response.setStatus(204);
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String requestString = request.getReader().lines().collect(Collectors.joining());
        logger.info("Received request body; {}.", requestString);
        InvokeRequest invokeRequest = objectMapper.readValue(requestString, InvokeRequest.class);

        String command = invokeRequest.getCommand();

        CommandSession commandSession = new CommandSession(readOnlyChannels);
        String sessionId = commandSession.getSessionId();

        PtyMaster ptyMaster = new PtyMaster(command, stdOut -> handleOutput(commandSession, stdOut), (nul) -> {});
        ptyMaster.setChangeHandler((oldStatus, newStatus) -> {
            if (newStatus.isFinal()) {
                onComplete(commandSession, newStatus, invokeRequest.getRequest());
            }
        });
        commandSession.setPtyMaster(ptyMaster);
        sessionRegistry.put(commandSession);

        ptyMaster.start();

        //write response
        InvokeResponse invokeResponse = new InvokeResponse(sessionId);
        response.getWriter().write(objectMapper.writeValueAsString(invokeResponse));
    }

    private void handleOutput(CommandSession commandSession, int[] stdOut) {
        byte[] buffer = Arrays.charIntstoBytes(stdOut, StandardCharsets.UTF_8);
        stdoutChecksum.add(buffer);
        commandSession.handleOutput(buffer);
    }

    private void onComplete(CommandSession commandSession, Status newStatus, Request callback) {
        TaskStatusUpdateEvent.Builder updateEventBuilder = TaskStatusUpdateEvent.newBuilder();
        try {
            String digest = stdoutChecksum.digest();
            commandSession.close();
            updateEventBuilder
                    .taskId(commandSession.getSessionId())
                    .newStatus(StatusConverter.fromTermdStatus(newStatus))
                    .outputChecksum(digest);
        } catch (IOException e) {
            updateEventBuilder
                    .taskId(commandSession.getSessionId())
                    .newStatus(org.jboss.pnc.buildagent.api.Status.FAILED)
                    .message("Unable to flush stdout: " + e.getMessage());
        }

        //notify completion via callback
        CompletableFuture<HttpClient.Response> responseFuture = new CompletableFuture<>();
        try {
            String data = objectMapper.writeValueAsString(updateEventBuilder.build());
            httpClient.invoke(
                    callback.getUrl().toURI(),
                    callback.getMethod(),
                    callback.getHeaders(),
                    data,
                    responseFuture,
                    retryConfig.getMaxRetries(),
                    retryConfig.getWaitBeforeRetry());
        } catch (JsonProcessingException e) {
            logger.error("Cannot serialize invoke object.", e);
        } catch (URISyntaxException e) {
            logger.error("Invalid callback url.", e);
        }
    }
}
