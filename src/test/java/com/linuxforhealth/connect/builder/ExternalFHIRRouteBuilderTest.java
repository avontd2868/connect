package com.linuxforhealth.connect.builder;

import com.linuxforhealth.connect.support.TestUtils;
import org.apache.camel.Exchange;
import org.apache.camel.RoutesBuilder;
import org.apache.camel.component.mock.MockEndpoint;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Properties;

/**
 * Tests external FHIR R4 route exposed by {@link FhirR4RouteBuilder#EXTERNAL_FHIR_ROUTE_URI}
 */
public class ExternalFHIRRouteBuilderTest extends RouteTestSupport{

    private MockEndpoint mockResult;

    @Override
    protected RoutesBuilder createRouteBuilder() throws Exception {
        return new FhirR4RouteBuilder();
    }

    /**
     * Overriden to set the "fhir external servers" property
     * @return LFH application {@link Properties}
     */
    @Override
    protected Properties useOverridePropertiesWithPropertiesComponent() {
        Properties props =  super.useOverridePropertiesWithPropertiesComponent();
        props.setProperty("lfh.connect.fhir-r4.externalservers",
                "http://localhost?bridgeEndpoint=true&authMethod=Basic&authMethodPriority=Basic,Digest&authUsername=user&authPassword=pwd;http://localhost;http://localhost:8010/");
        return props;
    }

    /**
     * Overridden to register beans, apply advice, and register a mock endpoint
     * @throws Exception if an error occurs applying advice
     */
    @BeforeEach
    @Override
    protected void configureContext() throws Exception {
        mockProducerEndpointById(
                FhirR4RouteBuilder.EXTERNAL_FHIR_ROUTE_ID,
                FhirR4RouteBuilder.EXTERNAL_FHIR_PRODUCER_ID,
                "mock:result"
        );

        super.configureContext();

        mockResult = MockEndpoint.resolve(context, "mock:result");
    }


    /**
     * Tests the {@link FhirR4RouteBuilder#EXTERNAL_FHIR_ROUTE_URI} route.
     * @throws Exception
     */
    @Test
    void testRoute() throws Exception{
        String testMessage = context
                .getTypeConverter()
                .convertTo(String.class, TestUtils.getMessageAsString(
                        "fhir",
                        "ext-fhir-server-route-input.json",
                        System.lineSeparator()));

        JSONObject jsonMessage = new JSONObject(testMessage);

        mockResult.expectedPropertyReceived("result", jsonMessage.toString());
        mockResult.expectedHeaderReceived(Exchange.HTTP_METHOD, "POST");
        mockResult.expectedHeaderReceived("Prefer", "return=OperationOutcome");
        mockResult.expectedMessageCount(1);

        String expectedRecipientList = "http://localhost/Patient?bridgeEndpoint=true&authMethod=Basic&authMethodPriority=Basic,Digest&authUsername=user&authPassword=pwd;http://localhost/Patient;http://localhost:8010/Patient";
        mockResult.expectedHeaderReceived("recipientList", expectedRecipientList);

        fluentTemplate.to(FhirR4RouteBuilder.EXTERNAL_FHIR_ROUTE_URI)
                .withBody(jsonMessage.toString())
                .withHeader("resource", "Patient")
                .send();

        mockResult.assertIsSatisfied();
    }
}
