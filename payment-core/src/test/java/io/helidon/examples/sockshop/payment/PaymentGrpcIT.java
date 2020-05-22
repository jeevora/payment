package io.helidon.examples.sockshop.payment;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import io.grpc.inprocess.InProcessChannelBuilder;

import io.helidon.grpc.server.GrpcServer;
import io.helidon.microprofile.grpc.client.GrpcClientProxyBuilder;
import io.helidon.microprofile.server.Server;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.helidon.examples.sockshop.payment.TestDataFactory.auth;
import static io.helidon.examples.sockshop.payment.TestDataFactory.paymentRequest;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

/**
 * Integration tests for {@link PaymentGrpc}.
 */
public class PaymentGrpcIT {
    protected static Server SERVER;

    /**
     * This will start the application on ephemeral port to avoid port conflicts.
     * We can discover the actual port by calling {@link Server#port()} method afterwards.
     */
    @BeforeAll
    static void startServer() {
        // disable global tracing so we can start server in multiple test suites
        System.setProperty("tracing.global", "false");
        System.setProperty("grpc.port", "0");
        SERVER = Server.builder().port(0).build().start();
    }

    /**
     * Stop the server, as we cannot have multiple servers started at the same time.
     */
    @AfterAll
    static void stopServer() {
        SERVER.stop();
    }

    private TestPaymentRepository payments;

    private PaymentClient client;

    @BeforeEach
    void setup() {
        GrpcServer grpcServer = SERVER.cdiContainer().select(GrpcServer.class).get();
        client = GrpcClientProxyBuilder.create(InProcessChannelBuilder.forName(grpcServer.configuration().name()).usePlaintext().build(),
                                               PaymentClient.class).build();
        payments = SERVER.cdiContainer().select(TestPaymentRepository.class).get();
        payments.clear();
    }

    @Test
    void testSuccessfulAuthorization() {
        Authorization authorization = client.authorize(paymentRequest("A123", 50));
        assertThat(authorization.isAuthorised(), is(true));
        assertThat(authorization.getMessage(), is("Payment authorized."));
    }

    @Test
    void testDeclinedAuthorization() {
        Authorization authorization = client.authorize(paymentRequest("A123", 150));
        assertThat(authorization.isAuthorised(), is(false));
        assertThat(authorization.getMessage(), is("Payment declined: amount exceeds 100.00"));
    }

    @Test
    void testInvalidPaymentAmount() {
        Authorization authorization = client.authorize(paymentRequest("A123", -50));
        assertThat(authorization.isAuthorised(), is(false));
        assertThat(authorization.getMessage(), is("Invalid payment amount."));
    }

    @Test
    void testFindPaymentsByOrder() {
        LocalDateTime time = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        payments.saveAuthorization(auth("A123", time, new Err("Payment service unavailable")));
        payments.saveAuthorization(auth("A123", time.plusSeconds(5), false, "Payment declined"));
        payments.saveAuthorization(auth("A123", time.plusSeconds(10), true, "Payment processed"));
        payments.saveAuthorization(auth("B456", time, true, "Payment processed"));

        assertThat(client.getOrderAuthorizations("A123"), hasSize(3));
        assertThat(client.getOrderAuthorizations("B456"), hasSize(1));
        assertThat(client.getOrderAuthorizations("C789"), hasSize(0));
    }
}