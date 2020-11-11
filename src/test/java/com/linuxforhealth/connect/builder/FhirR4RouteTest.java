/*
 * (C) Copyright IBM Corp. 2020
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.linuxforhealth.connect.builder;

import com.linuxforhealth.connect.support.LinuxForHealthAssertions;
import com.linuxforhealth.connect.support.TestUtils;
import org.apache.camel.Exchange;
import org.apache.camel.RoutesBuilder;
import org.apache.camel.component.mock.MockEndpoint;
import org.apache.camel.spi.PropertiesComponent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Properties;
import java.util.UUID;

/**
 * Tests {@link FhirR4RouteBuilder}
 */
public class FhirR4RouteTest extends RouteTestSupport {

    private MockEndpoint mockResult;

    private MockEndpoint mockExternalEndpoint;

    @Override
    protected RoutesBuilder createRouteBuilder() throws Exception {
        return new FhirR4RouteBuilder();
    }

    @Override
    protected Properties useOverridePropertiesWithPropertiesComponent() {
        Properties props =  super.useOverridePropertiesWithPropertiesComponent();
        props.remove("lfh.connect.fhir-r4.externalservers");
        return props;
    }

    /**
     * Overridden to register beans, apply advice, and register a mock endpoint
     *
     * @throws Exception if an error occurs applying advice
     */
    @BeforeEach
    @Override
    protected void configureContext() throws Exception {

        mockResult = mockProducerEndpoint(FhirR4RouteBuilder.ROUTE_ID,
                LinuxForHealthRouteBuilder.STORE_AND_NOTIFY_CONSUMER_URI,
                "mock:result");

        mockExternalEndpoint = mockProducerEndpoint(FhirR4RouteBuilder.ROUTE_ID,
                FhirR4RouteBuilder.EXTERNAL_FHIR_ROUTE_URI,
                "mock:external");

        super.configureContext();

        mockResult = MockEndpoint.resolve(context, "mock:result");
    }

    /**
     * Tests {@link FhirR4RouteBuilder#ROUTE_ID} where an external fhir server is not specified.
     *
     * @throws Exception
     */
    @Test
    void testRoute() throws Exception {
        String testMessage = context
                .getTypeConverter()
                .convertTo(String.class, TestUtils.getMessage("fhir", "fhir-r4-patient-bundle.json"))
                .replace(System.lineSeparator(), "");

        String expectedMessage = Base64.getEncoder().encodeToString(testMessage.getBytes(StandardCharsets.UTF_8));

        mockResult.expectedMessageCount(1);
        mockResult.expectedBodiesReceived(expectedMessage);
        mockResult.expectedPropertyReceived("dataStoreUri", "kafka:FHIR-R4_PATIENT?brokers=localhost:9094");
        mockResult.expectedPropertyReceived("dataFormat", "FHIR-R4");
        mockResult.expectedPropertyReceived("messageType", "PATIENT");
        mockResult.expectedPropertyReceived("routeId", "fhir-r4");

        mockExternalEndpoint.expectedMessageCount(0);

        fluentTemplate.to("http://0.0.0.0:8080/fhir/r4/Patient")
                .withBody(testMessage)
                .send();

        mockResult.assertIsSatisfied();
        mockExternalEndpoint.assertIsSatisfied();

        String expectedRouteUri = "jetty:http://0.0.0.0:8080/fhir/r4/Patient?httpMethodRestrict=POST";
        String actualRouteUri = mockResult.getExchanges().get(0).getProperty("routeUri", String.class);
        LinuxForHealthAssertions.assertEndpointUriSame(expectedRouteUri, actualRouteUri);

        Exchange mockExchange = mockResult.getExchanges().get(0);

        Long actualTimestamp = mockExchange.getProperty("timestamp", Long.class);
        Assertions.assertNotNull(actualTimestamp);
        Assertions.assertTrue(actualTimestamp > 0);

        UUID actualUuid = UUID.fromString(mockExchange.getProperty("uuid", String.class));
        Assertions.assertEquals(36, actualUuid.toString().length());
    }

    /**
     * Tests {@link FhirR4RouteBuilder#ROUTE_ID} where an external fhir server is specified.
     *
     * @throws Exception
     */
    @Test
    void testRouteWithExternalServers() throws Exception {
        // add external server property
        PropertiesComponent contextPropertiesComponent = context.getPropertiesComponent();
        contextPropertiesComponent.addLocation("application.properties");

        Properties overrideProperties = new Properties();
        overrideProperties.setProperty("lfh.connect.fhir-r4.externalservers", "http://localhost:9000/fhir?foo=bar");

        contextPropertiesComponent.setOverrideProperties(overrideProperties);
        contextPropertiesComponent.loadProperties();

        String testMessage = context
                .getTypeConverter()
                .convertTo(String.class, TestUtils.getMessage("fhir", "fhir-r4-patient-bundle.json"))
                .replace(System.lineSeparator(), "");

        mockExternalEndpoint.expectedMessageCount(1);
        mockResult.expectedMessageCount(1);

        fluentTemplate.to("http://0.0.0.0:8080/fhir/r4/Patient")
                .withBody(testMessage)
                .send();

        mockExternalEndpoint.assertIsSatisfied();
        mockResult.expectedMessageCount(1);
    }
}
