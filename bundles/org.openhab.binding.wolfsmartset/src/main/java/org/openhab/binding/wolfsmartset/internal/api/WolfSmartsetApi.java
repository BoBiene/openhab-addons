/**
 * Copyright (c) 2010-2021 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.wolfsmartset.internal.api;

import java.net.CookieStore;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpResponseException;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.util.StringContentProvider;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.openhab.binding.wolfsmartset.internal.dto.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSyntaxException;

/**
 * The {@link WolfSmartsetCloudConnector} class is used for connecting to the Wolf Smartset cloud service
 *
 * @author Bo Biene - Initial contribution
 */
@NonNullByDefault
public class WolfSmartsetApi {
    private static final int MAX_QUEUE_SIZE = 1000; // maximum queue size
    private static final int REQUEST_TIMEOUT_SECONDS = 10;
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();
    private static final DateTimeFormatter SESSION_TIME_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private String username;
    private String password;
    private String serviceToken = "";
    private @Nullable CreateSession2DTO session = null;
    private int loginFailedCounter = 0;
    private HttpClient httpClient;
    private int delay = 500; // in ms
    private final ScheduledExecutorService scheduler;
    private final LinkedBlockingQueue<RequestQueueEntry> requestQueue = new LinkedBlockingQueue<>(MAX_QUEUE_SIZE);
    private @Nullable ScheduledFuture<?> processJob;

    private final Logger logger = LoggerFactory.getLogger(WolfSmartsetApi.class);

    public WolfSmartsetApi(String username, String password, HttpClient httpClient, ScheduledExecutorService scheduler)
            throws WolfSmartsetCloudException {
        this.username = username;
        this.password = password;
        this.httpClient = httpClient;
        this.scheduler = scheduler;
        if (!checkCredentials()) {
            throw new WolfSmartsetCloudException("username or password can't be empty");
        }
    }

    /**
     * Validate Login to wolf smartset. Returns true if valid token is available, otherwise tries to authenticate with wolf smartset portal
     */
    public synchronized boolean login() {
        if (!checkCredentials()) {
            return false;
        }
        if (!serviceToken.isEmpty()) {
            return true;
        }
        logger.debug("Wolf Smartset login with username {}", username);
        try {
            if (loginRequest()) {
                loginFailedCounter = 0;
                this.session = getCreateSession();
                if (this.session != null) {

                    logger.debug("login successfull, browserSessionId {}", session.getBrowserSessionId());
                    return true;

                } else {
                    loginFailedCounter++;
                    this.session = null;
                    logger.trace("Login succeded but failed to create session {}", loginFailedCounter);
                    return false;
                }
            } else {
                loginFailedCounter++;
                logger.debug("Wolf Smartset login attempt {}", loginFailedCounter);
                return false;
            }
        } catch (WolfSmartsetCloudException e) {
            logger.info("Error logging on to Wolf Smartset ({}): {}", loginFailedCounter, e.getMessage());
            loginFailedCounter++;
            serviceToken = "";
            loginFailedCounterCheck();
            return false;
        }
    }

    /**
     * Request the systems available for the authenticated account
     * @return 
     */
    public List<GetSystemListDTO> getSystems() {
        final String response = getSystemString();
        List<GetSystemListDTO> devicesList = new ArrayList<>();
        try {
            GetSystemListDTO[] cdl = GSON.fromJson(response, GetSystemListDTO[].class);
            if (cdl != null) {
                for (GetSystemListDTO system : cdl) {
                    devicesList.add(system);
                }
            }
        } catch (JsonSyntaxException | IllegalStateException | ClassCastException e) {
            loginFailedCounter++;
            logger.info("Error while parsing devices: {}", e.getMessage());
        }
        return devicesList;
    }

    /**
     * Request the description of the given system
     * @param systemId
     * @param gatewayId
     * @return
     */
    public @Nullable GetGuiDescriptionForGatewayDTO getSystemDescription(Integer systemId, Integer gatewayId) {
        final String response = getSystemDescriptionString(systemId, gatewayId);
        GetGuiDescriptionForGatewayDTO deviceDescription = null;
        try {
            deviceDescription = GSON.fromJson(response, GetGuiDescriptionForGatewayDTO.class);
        } catch (JsonSyntaxException | IllegalStateException | ClassCastException e) {
            loginFailedCounter++;
            logger.info("Error while parsing device descriptions: {}", e.getMessage());
        }
        return deviceDescription;
    }

    /**
     * Request the system state of the given systems
     * @param systems
     * @return
     */
    public @Nullable GetSystemStateListDTO @Nullable [] getSystemState(Collection<@Nullable GetSystemListDTO> systems) {
        final String response = getSystemStateString(systems);
        GetSystemStateListDTO[] systemState = null;
        try {
            systemState = GSON.fromJson(response, GetSystemStateListDTO[].class);
        } catch (JsonSyntaxException | IllegalStateException | ClassCastException e) {
            loginFailedCounter++;
            logger.info("Error while parsing device descriptions: {}", e.getMessage());
        }
        if (systemState != null && systemState.length >= 1)
            return systemState;
        else
            return null;
    }

    /**
     * Request the fault messages of the given system
     * @param systemId
     * @param gatewayId
     * @return
     */
    public @Nullable ReadFaultMessagesDTO getFaultMessages(Integer systemId, Integer gatewayId) {
        final String response = getFaultMessagesString(systemId, gatewayId);
        ReadFaultMessagesDTO faultMessages = null;
        try {
            faultMessages = GSON.fromJson(response, ReadFaultMessagesDTO.class);
        } catch (JsonSyntaxException | IllegalStateException | ClassCastException e) {
            loginFailedCounter++;
            logger.info("Error while parsing faultmessages: {}", e.getMessage());
        }
        return faultMessages;
    }
    
    /**
     * request the current values for a unit associated with the given system.
     * if lastAccess is not null, only value changes newer than the given timestamp are returned
     * @param systemId
     * @param gatewayId
     * @param bundleId the id of the Unit
     * @param valueIdList list of the values to request
     * @param lastAccess timestamp of the last valid value request
     * @return
     */
    public @Nullable GetParameterValuesDTO getGetParameterValues(Integer systemId, Integer gatewayId, Long bundleId,
            List<Long> valueIdList, @Nullable Instant lastAccess) {
        final String response = getGetParameterValuesString(systemId, gatewayId, bundleId, valueIdList, lastAccess);
        GetParameterValuesDTO parameterValues = null;
        try {
            parameterValues = GSON.fromJson(response, GetParameterValuesDTO.class);
        } catch (JsonSyntaxException | IllegalStateException | ClassCastException e) {
            loginFailedCounter++;
            logger.info("Error while parsing device parameter values: {}", e.getMessage());
        }
        return parameterValues;
    }

    private void startClient() throws WolfSmartsetCloudException {
        if (!httpClient.isStarted()) {
            try {
                httpClient.start();
            } catch (Exception e) {
                throw new WolfSmartsetCloudException("No http client cannot be started: " + e.getMessage(), e);
            }
        }
        setDelay(delay);
    }

    public void stopClient() {
        try {
            stopProcessJob();
            requestQueue.forEach(queueEntry -> queueEntry.future.completeExceptionally(new CancellationException()));
            this.httpClient.stop();
        } catch (Exception e) {
            logger.debug("Error stopping httpclient :{}", e.getMessage(), e);
        }
    }

    /**
     * Set a new delay
     *
     * @param delay in ms between to requests
     */
    private void setDelay(int delay) {
        if (delay < 0) {
            throw new IllegalArgumentException("Delay needs to be larger or equal to zero");
        }
        this.delay = delay;
        stopProcessJob();
        if (delay != 0) {
            processJob = scheduler.scheduleWithFixedDelay(this::processQueue, 0, delay, TimeUnit.MILLISECONDS);
        }
    }

    private boolean checkCredentials() {
        if (username.trim().isEmpty() || password.trim().isEmpty()) {
            logger.info("Wolf Smartset: username or password missing.");
            return false;
        }
        return true;
    }

    private String getApiUrl() {
        return "https://www.wolf-smartset.com/portal/";
    }
 
    private String getCreateSessionString() {
        String resp = "";
        try {
            JsonObject json = new JsonObject();
            json.addProperty("Timestamp", SESSION_TIME_STAMP.format(LocalDateTime.now()));
            resp = requestPOST("api/portal/CreateSession2", json).get();
            logger.trace("api/portal/CreateSession2 response: {}", resp);
        } catch (InterruptedException | ExecutionException | WolfSmartsetCloudException e) {
            logger.info("{}", e.getMessage());
            loginFailedCounter++;
        }
        return resp;
    }

    private @Nullable CreateSession2DTO getCreateSession() {
        final String response = getCreateSessionString();
        CreateSession2DTO session = null;
        try {
            session = GSON.fromJson(response, CreateSession2DTO.class);

        } catch (JsonSyntaxException | IllegalStateException | ClassCastException e) {
            loginFailedCounter++;
            logger.info("Error while parsing CreateSession2DTO: {}", e.getMessage());
        }
        return session;
    }

    private String getSystemString() {
        String resp = "";
        try {
            resp = requestGET("api/portal/GetSystemList").get();
            logger.trace("api/portal/GetSystemList response: {}", resp);
        } catch (InterruptedException | ExecutionException | WolfSmartsetCloudException e) {
            logger.info("{}", e.getMessage());
            loginFailedCounter++;
        }
        return resp;
    }

    private String getSystemDescriptionString(Integer systemId, Integer gatewayId) {
        String resp = "";
        try {
            Map<String, String> params = new HashMap<String, String>();
            params.put("SystemId", systemId.toString());
            params.put("GatewayId", gatewayId.toString());
            resp = requestGET("api/portal/GetGuiDescriptionForGateway", params).get();
            logger.trace("api/portal/GetGuiDescriptionForGateway response: {}", resp);
        } catch (InterruptedException | ExecutionException | WolfSmartsetCloudException e) {
            logger.info("{}", e.getMessage());
            loginFailedCounter++;
        }
        return resp;
    }

    private String getSystemStateString(Collection<@Nullable GetSystemListDTO> systems) {
        String resp = "";
        try {
            JsonArray jsonSystemList = new JsonArray();

            for (@Nullable
            GetSystemListDTO system : systems) {
                if (system != null) {
                    JsonObject jsonSystem = new JsonObject();
                    jsonSystem.addProperty("SystemId", system.getId());
                    jsonSystem.addProperty("GatewayId", system.getGatewayId());

                    if (system.getSystemShareId() != null) {
                        jsonSystem.addProperty("SystemShareId", system.getSystemShareId());
                    }
                    jsonSystemList.add(jsonSystem);
                }
            }

            JsonObject json = new JsonObject();

            json.add("SystemList", jsonSystemList);
            resp = requestPOST("api/portal/GetSystemStateList", json).get();
            logger.trace("api/portal/GetSystemStateList response: {}", resp);
        } catch (InterruptedException | ExecutionException | WolfSmartsetCloudException e) {
            logger.info("{}", e.getMessage());
            loginFailedCounter++;
        }
        return resp;
    }

    private String getFaultMessagesString(Integer systemId, Integer gatewayId) {
        String resp = "";
        try {
            JsonObject json = new JsonObject();

            json.addProperty("SystemId", systemId);
            json.addProperty("GatewayId", gatewayId);
            resp = requestPOST("api/portal/ReadFaultMessages", json).get();
            logger.trace("api/portal/ReadFaultMessages response: {}", resp);
        } catch (InterruptedException | ExecutionException | WolfSmartsetCloudException e) {
            logger.info("{}", e.getMessage());
            loginFailedCounter++;
        }
        return resp;
    }
   
    private String getGetParameterValuesString(Integer systemId, Integer gatewayId, Long bundleId,
            List<Long> valueIdList, @Nullable Instant lastAccess) {
        String resp = "";
        try {
            JsonObject json = new JsonObject();
            json.addProperty("SystemId", systemId);
            json.addProperty("GatewayId", gatewayId);
            json.addProperty("BundleId", bundleId);
            json.addProperty("IsSubBundle", false);
            json.add("ValueIdList", GSON.toJsonTree(valueIdList));
            if (lastAccess != null)
                json.addProperty("LastAccess", DateTimeFormatter.ISO_INSTANT.format(lastAccess));
            else
                json.addProperty("LastAccess", (String) null);
            json.addProperty("GuiIdChanged", false);
            if (session != null)
                json.addProperty("SessionId", session.getBrowserSessionId());
            resp = requestPOST("api/portal/GetParameterValues", json).get();
            logger.trace("api/portal/GetParameterValues response: {}", resp);
        } catch (InterruptedException | ExecutionException | WolfSmartsetCloudException e) {
            logger.info("{}", e.getMessage());
            loginFailedCounter++;
        }
        return resp;
    }

    private CompletableFuture<String> requestGET(String url) throws WolfSmartsetCloudException {
        return requestGET(url, new HashMap<String, String>());
    }

    private CompletableFuture<String> requestGET(String url, Map<String, String> params)
            throws WolfSmartsetCloudException {
        return rateLimtedRequest(() -> {
            if (this.serviceToken.isEmpty()) {
                throw new WolfSmartsetCloudException("Cannot execute request. service token missing");
            }
            loginFailedCounterCheck();
            startClient();

            var requestUrl = getApiUrl() + url;
            Request request = httpClient.newRequest(requestUrl).timeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            request.header(HttpHeader.AUTHORIZATION, serviceToken);
            request.method(HttpMethod.GET);
            request.header(HttpHeader.CONTENT_TYPE, "application/x-www-form-urlencoded");

            for (Entry<String, String> entry : params.entrySet()) {
                logger.debug("Send request param: {}={} to {}", entry.getKey(), entry.getValue().toString(), url);
                request.param(entry.getKey(), entry.getValue());
            }

            return request;
        });
    }

    private CompletableFuture<String> requestPOST(String url, JsonElement json) throws WolfSmartsetCloudException {
        return rateLimtedRequest(() -> {
            if (this.serviceToken.isEmpty()) {
                throw new WolfSmartsetCloudException("Cannot execute request. service token missing");
            }
            loginFailedCounterCheck();
            startClient();

            var request = createPOSTRequest(url, json);
            request.header(HttpHeader.AUTHORIZATION, serviceToken);
            return request;
        });
    }

    private Request createPOSTRequest(String url, JsonElement json) {
        var requestUrl = getApiUrl() + url;
        Request request = httpClient.newRequest(requestUrl).timeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        request.header(HttpHeader.ACCEPT, "application/json");
        request.header(HttpHeader.CONTENT_TYPE, "application/json");
        request.method(HttpMethod.POST);

        request.content(new StringContentProvider(json.toString()), "application/json");
        return request;
    }

    private CompletableFuture<String> rateLimtedRequest(SupplyRequestFunctionalInterface buildRequest) {
        // if no delay is set, return a completed CompletableFuture
        CompletableFuture<String> future = new CompletableFuture<>();
        RequestQueueEntry queueEntry = new RequestQueueEntry(buildRequest, future);
        if (delay == 0) {
            queueEntry.completeFuture((r) -> this.getResponse(r));
        } else {
            if (!requestQueue.offer(queueEntry)) {
                future.completeExceptionally(new RejectedExecutionException("Maximum queue size exceeded."));
            }
        }
        return future;
    }

    private void stopProcessJob() {
        ScheduledFuture<?> processJob = this.processJob;
        if (processJob != null) {
            processJob.cancel(false);
            this.processJob = null;
        }
    }

    private void processQueue() {
        RequestQueueEntry queueEntry = requestQueue.poll();
        if (queueEntry != null) {
            queueEntry.completeFuture((r) -> this.getResponse(r));
        }
    }

    @FunctionalInterface
    interface SupplyRequestFunctionalInterface {
        Request get() throws WolfSmartsetCloudException;
    }

    @FunctionalInterface
    interface GetResponseFunctionalInterface {
        String get(Request request) throws WolfSmartsetCloudException;
    }

    private String getResponse(Request request) throws WolfSmartsetCloudException {
        try {
            logger.debug("execute request {} {}", request.getMethod(), request.getURI());
            final ContentResponse response = request.send();
            if (response.getStatus() == HttpStatus.NOT_FOUND_404) {
                throw new WolfSmartsetCloudException("Invalid request, not found " + request.getURI());
            } else if (response.getStatus() == HttpStatus.TOO_MANY_REQUESTS_429) {
                Thread.sleep(30000);
                throw new WolfSmartsetCloudException("Error too many requests: " + response.getContentAsString());
            } else if (response.getStatus() >= HttpStatus.BAD_REQUEST_400
                    && response.getStatus() < HttpStatus.INTERNAL_SERVER_ERROR_500) {
                this.serviceToken = "";
            } else if (response.getStatus() == HttpStatus.BAD_REQUEST_400) {
                throw new WolfSmartsetCloudException("Invalid request: " + response.getContentAsString());
            }
            return response.getContentAsString();
        } catch (HttpResponseException e) {
            serviceToken = "";
            logger.debug("Error while executing request to {} :{}", request.getURI(), e.getMessage());
            loginFailedCounter++;
        } catch (InterruptedException | TimeoutException | ExecutionException /* | IOException */ e) {
            logger.debug("Error while executing request to {} :{}", request.getURI(), e.getMessage());
            loginFailedCounter++;
        }
        return "";
    }

    void loginFailedCounterCheck() {
        if (loginFailedCounter > 10) {
            logger.warn("Repeated errors logging on to Wolf Smartset");
            serviceToken = "";
            loginFailedCounter = 0;
        }
    }

    protected boolean loginRequest() throws WolfSmartsetCloudException {
        try {
            startClient();
            logger.trace("Wolf Smartset Login");

            String url = getApiUrl() + "connect/token";
            Request request = httpClient.POST(url).timeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            request.header(HttpHeader.CONTENT_TYPE, "application/x-www-form-urlencoded");

            var encodedUser = URLEncoder.encode(username, StandardCharsets.UTF_8);
            var encodedPassword = URLEncoder.encode(password, StandardCharsets.UTF_8);
            var authRequestBody = "grant_type=password&username=" + encodedUser + "&password=" + encodedPassword;

            request.content(new StringContentProvider("application/x-www-form-urlencoded", authRequestBody,
                    StandardCharsets.UTF_8));

            final ContentResponse response;
            response = request.send();

            final String content = response.getContentAsString();
            logger.trace("Wolf smartset Login response= {}", response);
            logger.trace("Wolf smartset Login content= {}", content);

            switch (response.getStatus()) {

                case HttpStatus.FORBIDDEN_403:
                    throw new WolfSmartsetCloudException(

                            "Access denied. Did you set the correct password and/or username?");

                case HttpStatus.OK_200:

                    LoginResponseDTO jsonResp = GSON.fromJson(content, LoginResponseDTO.class);
                    if (jsonResp == null) {
                        throw new WolfSmartsetCloudException("Error getting logon details: " + content);
                    }

                    serviceToken = jsonResp.getTokenType() + " " + jsonResp.getAccessToken();

                    logger.trace("Wolf Smartset login scope = {}", jsonResp.getScope());
                    logger.trace("Wolf Smartset login expiresIn = {}", jsonResp.getExpiresIn());
                    logger.trace("Wolf Smartset login tokenType = {}", jsonResp.getTokenType());
                    return true;
                default:
                    logger.trace("request returned status '{}', reason: {}, content = {}", response.getStatus(),
                            response.getReason(), response.getContentAsString());
                    throw new WolfSmartsetCloudException(response.getStatus() + response.getReason());
            }
        } catch (InterruptedException | TimeoutException | ExecutionException | JsonParseException e) {
            throw new WolfSmartsetCloudException("Cannot logon to Wolf Smartset cloud: " + e.getMessage(), e);
        }
    }

    private static class RequestQueueEntry {
        private SupplyRequestFunctionalInterface buildRequest;
        private CompletableFuture<String> future;

        public RequestQueueEntry(SupplyRequestFunctionalInterface buildRequest, CompletableFuture<String> future) {
            this.buildRequest = buildRequest;
            this.future = future;
        }

        public void completeFuture(GetResponseFunctionalInterface getResponse) {
            try {
                String response = getResponse.get(this.buildRequest.get());
                future.complete(response);
            } catch (Throwable e) {
                future.completeExceptionally(e);
            }
        }
    }
}
