/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.extensions.rest;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.client.node.NodeClient;
import org.opensearch.common.bytes.BytesReference;
import org.opensearch.common.io.stream.StreamInput;
import org.opensearch.common.xcontent.XContentType;
import org.opensearch.extensions.DiscoveryExtension;
import org.opensearch.extensions.ExtensionsOrchestrator;
import org.opensearch.identity.ExtensionTokenProcessor;
import org.opensearch.identity.PrincipalIdentifierToken;
import org.opensearch.rest.BaseRestHandler;
import org.opensearch.rest.BytesRestResponse;
import org.opensearch.rest.RestRequest;
import org.opensearch.rest.RestRequest.Method;
import org.opensearch.rest.RestStatus;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.TransportException;
import org.opensearch.transport.TransportResponseHandler;
import org.opensearch.transport.TransportService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static java.util.Collections.emptyMap;
import static java.util.Collections.unmodifiableList;

/**
 * An action that forwards REST requests to an extension
 */
public class RestSendToExtensionAction extends BaseRestHandler {

    private static final String SEND_TO_EXTENSION_ACTION = "send_to_extension_action";
    private static final Logger logger = LogManager.getLogger(RestSendToExtensionAction.class);
    private static final String CONSUMED_PARAMS_KEY = "extension.consumed.parameters";
    private static final String CONSUMED_CONTENT_KEY = "extension.consumed.content";
    // To replace with user identity see https://github.com/opensearch-project/OpenSearch/pull/4247
    private static final Principal DEFAULT_PRINCIPAL = new Principal() {
        @Override
        public String getName() {
            return "OpenSearchUser";
        }
    };

    private final List<Route> routes;
    private final String pathPrefix;
    private final DiscoveryExtension discoveryExtension;
    private final TransportService transportService;

    /**
     * Instantiates this object using a {@link RegisterRestActionsRequest} to populate the routes.
     *
     * @param restActionsRequest A request encapsulating a list of Strings with the API methods and paths.
     * @param transportService The OpenSearch transport service
     * @param discoveryExtension The extension node to which to send actions
     */
    public RestSendToExtensionAction(
        RegisterRestActionsRequest restActionsRequest,
        DiscoveryExtension discoveryExtension,
        TransportService transportService
    ) {
        this.pathPrefix = "/_extensions/_" + restActionsRequest.getUniqueId();
        List<Route> restActionsAsRoutes = new ArrayList<>();
        for (String restAction : restActionsRequest.getRestActions()) {
            RestRequest.Method method;
            String path;
            try {
                int delim = restAction.indexOf(' ');
                method = RestRequest.Method.valueOf(restAction.substring(0, delim));
                path = pathPrefix + restAction.substring(delim).trim();
            } catch (IndexOutOfBoundsException | IllegalArgumentException e) {
                throw new IllegalArgumentException(restAction + " does not begin with a valid REST method");
            }
            logger.info("Registering: " + method + " " + path);
            restActionsAsRoutes.add(new Route(method, path));
        }
        this.routes = unmodifiableList(restActionsAsRoutes);
        this.discoveryExtension = discoveryExtension;
        this.transportService = transportService;
    }

    @Override
    public String getName() {
        return SEND_TO_EXTENSION_ACTION;
    }

    @Override
    public List<Route> routes() {
        return this.routes;
    }

    @Override
    public RestChannelConsumer prepareRequest(final RestRequest request, final NodeClient client) throws IOException {
        Method method = request.method();
        String path = request.path();
        Map<String, String> params = request.params();
        XContentType contentType = request.getXContentType();
        BytesReference content = request.content();

        if (path.startsWith(pathPrefix)) {
            path = path.substring(pathPrefix.length());
        }
        String message = "Forwarding the request " + method + " " + path + " to " + discoveryExtension;
        logger.info(message);
        // Initialize response. Values will be changed in the handler.
        final RestExecuteOnExtensionResponse restExecuteOnExtensionResponse = new RestExecuteOnExtensionResponse(
            RestStatus.INTERNAL_SERVER_ERROR,
            BytesRestResponse.TEXT_CONTENT_TYPE,
            message.getBytes(StandardCharsets.UTF_8),
            emptyMap()
        );
        final CountDownLatch inProgressLatch = new CountDownLatch(1);
        final TransportResponseHandler<RestExecuteOnExtensionResponse> restExecuteOnExtensionResponseHandler = new TransportResponseHandler<
            RestExecuteOnExtensionResponse>() {

            @Override
            public RestExecuteOnExtensionResponse read(StreamInput in) throws IOException {
                return new RestExecuteOnExtensionResponse(in);
            }

            @Override
            public void handleResponse(RestExecuteOnExtensionResponse response) {
                logger.info("Received response from extension: {}", response.getStatus());
                restExecuteOnExtensionResponse.setStatus(response.getStatus());
                restExecuteOnExtensionResponse.setContentType(response.getContentType());
                restExecuteOnExtensionResponse.setContent(response.getContent());
                // Extract the consumed parameters and content from the header
                Map<String, List<String>> headers = response.getHeaders();
                List<String> consumedParams = headers.get(CONSUMED_PARAMS_KEY);
                if (consumedParams != null) {
                    // consume each param
                    consumedParams.stream().forEach(p -> request.param(p));
                }
                List<String> consumedContent = headers.get(CONSUMED_CONTENT_KEY);
                if (consumedContent != null) {
                    // conditionally consume content
                    if (consumedParams.stream().filter(c -> Boolean.parseBoolean(c)).count() > 0) {
                        request.content();
                    }
                }
                Map<String, List<String>> headersWithoutConsumedParams = headers.entrySet()
                    .stream()
                    .filter(e -> !e.getKey().equals(CONSUMED_PARAMS_KEY))
                    .collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue()));
                restExecuteOnExtensionResponse.setHeaders(headersWithoutConsumedParams);
                inProgressLatch.countDown();
            }

            @Override
            public void handleException(TransportException exp) {
                logger.debug("REST request failed", exp);
                // Status is already defaulted to 500 (INTERNAL_SERVER_ERROR)
                byte[] responseBytes = ("Request failed: " + exp.getMessage()).getBytes(StandardCharsets.UTF_8);
                restExecuteOnExtensionResponse.setContent(responseBytes);
                inProgressLatch.countDown();
            }

            @Override
            public String executor() {
                return ThreadPool.Names.GENERIC;
            }
        };
        try {
            final ExtensionTokenProcessor extensionTokenProcessor = new ExtensionTokenProcessor(discoveryExtension.getId());
            final PrincipalIdentifierToken requestIssuerIdentity = extensionTokenProcessor.generateToken(DEFAULT_PRINCIPAL);

            transportService.sendRequest(
                discoveryExtension,
                ExtensionsOrchestrator.REQUEST_REST_EXECUTE_ON_EXTENSION_ACTION,
                // HERE BE DRAGONS - DO NOT INCLUDE HEADERS
                // SEE https://github.com/opensearch-project/OpenSearch/issues/4429
                new ExtensionRestRequest(method, path, params, contentType, content, requestIssuerIdentity),
                restExecuteOnExtensionResponseHandler
            );
            try {
                inProgressLatch.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                return channel -> channel.sendResponse(
                    new BytesRestResponse(RestStatus.REQUEST_TIMEOUT, "No response from extension to request.")
                );
            }
        } catch (Exception e) {
            logger.info("Failed to send REST Actions to extension " + discoveryExtension.getName(), e);
        }
        BytesRestResponse restResponse = new BytesRestResponse(
            restExecuteOnExtensionResponse.getStatus(),
            restExecuteOnExtensionResponse.getContentType(),
            restExecuteOnExtensionResponse.getContent()
        );
        for (Entry<String, List<String>> headerEntry : restExecuteOnExtensionResponse.getHeaders().entrySet()) {
            for (String value : headerEntry.getValue()) {
                restResponse.addHeader(headerEntry.getKey(), value);
            }
        }

        return channel -> channel.sendResponse(restResponse);
    }
}