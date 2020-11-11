/*
 * (C) Copyright IBM Corp. 2020
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.linuxforhealth.connect.builder;

import com.linuxforhealth.connect.processor.MetaDataProcessor;
import com.linuxforhealth.connect.support.CamelContextSupport;
import com.linuxforhealth.connect.support.ExternalServerAggregationStrategy;

import java.net.URI;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import org.apache.camel.Exchange;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Defines a FHIR R4 REST Processing route
 */
public class FhirR4RouteBuilder extends BaseRouteBuilder {

    private final Logger logger = LoggerFactory.getLogger(FhirR4RouteBuilder.class);

    public final static String ROUTE_ID = "fhir-r4";
    public final static String ROUTE_PRODUCER_ID = "fhir-r4-producer-store-and-notify";
    public final static String EXTERNAL_FHIR_ROUTE_URI = "direct:toExternalFhirServers";
    public final static String EXTERNAL_FHIR_ROUTE_ID = "external-fhir-servers";
    public final static String EXTERNAL_FHIR_PRODUCER_ID = "lfh-external-fhir-producer";
    public final static String RECIPIENT_LIST_DELIMITER = ";";

    @Override
    protected String getRoutePropertyNamespace() {
        return "lfh.connect.fhir-r4";
    }

    @Override
    protected void buildRoute(String routePropertyNamespace) {
        CamelContextSupport ctxSupport = new CamelContextSupport(getContext());
        String fhirUri = ctxSupport.getProperty("lfh.connect.fhir-r4.uri");
        rest(fhirUri)
            .post("/{resource}")
            .route()
            .routeId(ROUTE_ID)
            .unmarshal().fhirJson("R4")
            .marshal().fhirJson("R4")
            .process(new MetaDataProcessor(routePropertyNamespace))
            .to(LinuxForHealthRouteBuilder.STORE_AND_NOTIFY_CONSUMER_URI)
            .id(ROUTE_PRODUCER_ID)
            .choice()
                .when(simple("${properties:lfh.connect.fhir-r4.externalservers} != null"))
                    .to(EXTERNAL_FHIR_ROUTE_URI)
            .end();

        /*
         * Use the Camel Recipient List EIP to optionally send data to one or more external fhir servers
         * when sending FHIR resources to LinuxForHealth.
         
         * Set the lfh.connect.fhir-r4.externalservers property to a semi-colon delimited list of servers to
         * enable this feature.  Example:
         * lfh.connect.fhir-r4.externalservers=http://localhost:9081/fhir-server/api/v4;http://localhost:9083/fhir-server/api/v4
         *
         */
        from(EXTERNAL_FHIR_ROUTE_URI)
        .routeId(EXTERNAL_FHIR_ROUTE_ID)
        .process(exchange -> {
            // Save off the existing result in a property
            exchange.setProperty("result", exchange.getIn().getBody(String.class));

            // Decode the data and set as message body
            JSONObject msg = new JSONObject(exchange.getIn().getBody(String.class));
            byte[] body = Base64.getDecoder().decode(msg.getString("data"));
            exchange.getIn().setBody(body);

            // Set up for the recipient list outbound calls
            exchange.getIn().removeHeaders("Camel*");
            exchange.getIn().setHeader(Exchange.HTTP_METHOD, "POST");
            exchange.getIn().setHeader("Accept", "application/fhir+json");
            exchange.getIn().setHeader("Content-Type", "application/fhir+json");
            exchange.getIn().setHeader("Prefer", "return=OperationOutcome");

            String externalServers = simple("{{lfh.connect.fhir-r4.externalservers}}").evaluate(exchange, String.class);
            String resource = exchange.getIn().getHeader("resource", String.class);
            String recipients = createRecipientList(externalServers, resource);
            exchange.getIn().setHeader("recipientList", recipients);

            Arrays.stream(recipients.split(RECIPIENT_LIST_DELIMITER))
                    .forEach(r -> logger.info("Sending request to {}", r));
        })
        .recipientList(header("recipientList"), RECIPIENT_LIST_DELIMITER)
        .aggregationStrategy(new ExternalServerAggregationStrategy())
        .id(EXTERNAL_FHIR_PRODUCER_ID);
    }

    /**
     * Creates the recipient list string containing comma delimited external server uris for the specified FHIR resource.
     * This method preserves the external server uri query parameters, but does not clean or normalize the uri.
     * @param externalServers The external server list
     * @param fhirResource The fhir resource (Patient, Practitioner, etc)
     * @return {@link String[]} with a "|" delimiter
     */
    private String createRecipientList(String externalServers, String fhirResource) {
        String[] externalServerItems = externalServers.split(RECIPIENT_LIST_DELIMITER);
        String[] recipientUris = new String[externalServerItems.length];

        for (int i=0; i<externalServerItems.length; i++) {
            String[] serverTokens = externalServerItems[i].split("\\?");

            String serverUri = serverTokens[0];
            String serverParams = (serverTokens.length >= 2) ? serverTokens[1] : null;
            String recipientUri = serverUri.endsWith("/") ? serverUri + fhirResource : serverUri + "/" + fhirResource;

            if (serverParams != null) {
                recipientUri += "?" + serverParams;
            }
            recipientUris[i] = recipientUri;
        }
        return String.join(RECIPIENT_LIST_DELIMITER, recipientUris);
    }
}
