/**
 * Copyright 2015 Red Hat, Inc.
 */
package io.vertx.proton;

import io.vertx.core.AsyncResult;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import io.vertx.proton.impl.ProtonServerImpl;

import org.apache.qpid.proton.amqp.messaging.AmqpValue;
import org.apache.qpid.proton.amqp.messaging.Section;
import org.apache.qpid.proton.amqp.transport.Target;
import org.apache.qpid.proton.message.Message;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicInteger;

import static io.vertx.proton.ProtonHelper.message;
import static io.vertx.proton.ProtonHelper.tag;

@RunWith(VertxUnitRunner.class)
public class ProtonClientTest extends MockServerTestBase {

    private static Logger LOG = LoggerFactory.getLogger(ProtonClientTest.class);

    @Test
    public void testClientIdentification(TestContext context) {
        Async async = context.async();
        connect(context, connection -> {
            connection
                .setContainer("foo")
                .openHandler(x -> {
                    context.assertEquals("foo", connection.getContainer());
                    // Our mock server responds with a pong container id
                    context.assertEquals("pong: foo", connection.getRemoteContainer());
                    connection.disconnect();
                    async.complete();
                })
                .open();
        });
    }

    @Test
    public void testRemoteDisconnectHandling(TestContext context) {
        Async async = context.async();
        connect(context, connection->{
            context.assertFalse(connection.isDisconnected());
            connection.disconnectHandler(x ->{
                context.assertTrue(connection.isDisconnected());
                async.complete();
            });

            // Send a request to the server for him to disconnect us
            ProtonSender sender = connection.createSender(null).open();
            sender.send(tag(""), message("command", "disconnect"));
        });
    }

    @Test
    public void testLocalDisconnectHandling(TestContext context) {
        Async async = context.async();
        connect(context, connection -> {
            context.assertFalse(connection.isDisconnected());
            connection.disconnectHandler(x -> {
                context.assertTrue(connection.isDisconnected());
                async.complete();
            });
            // We will force the disconnection to the server
            connection.disconnect();
        });
    }

    @Test
    public void testRequestResponse(TestContext context) {
        sendReceiveEcho(context, "Hello World");
    }

    @Test
    public void testTransferLargeMessage(TestContext context) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < 1024*1024*5; i++) {
            builder.append('a'+(i%26));
        }
        sendReceiveEcho(context, builder.toString());
    }

    private void sendReceiveEcho(TestContext context, String data) {
        Async async = context.async();
        connect(context, connection -> {

            ProtonSession session = connection.session().open();
            session.receiver("echo")
                .handler((d, m) -> {
                    String actual = (String) (getMessageBody(context, m));
                    context.assertEquals(data, actual);
                    connection.disconnect();
                    async.complete();
                })
                .flow(10)
                .open();

            session.sender()
                .open()
                .send(tag(""), message("echo", data));


        });
    }

    @Test
    public void testReceiverAsyncSettle(TestContext context) {
        Async async = context.async();
        connect(context, connection -> {

            AtomicInteger counter = new AtomicInteger(0);
            ProtonSession session = connection.session().open();
            session.receiver().setSource("two_messages")
                .asyncHandler((d, m, settle) -> {
                    int count = counter.incrementAndGet();
                    switch (count) {
                        case 1: {
                            validateMessage(context, count, "Hello", m);

                            // On 1st message
                            // lets delay the settlement and credit..
                            vertx.setTimer(1000, x -> {
                                settle.run();
                            });

                            // We only flowed 1 credit, so we should not get
                            // another message until we run the settle callback
                            // above and issue another credit to the sender.
                            vertx.setTimer(500, x -> {
                                context.assertEquals(1, counter.get());
                            });
                            break;
                        }
                        case 2: {
                            validateMessage(context, count, "World", m);

                            // On 2nd message.. lets finish the test..
                            async.complete();
                            connection.disconnect();
                            break;
                        }
                    }
                })
                .flow(1)
                .open();
        });
    }

    @Test(timeout = 20000)
    public void testReceiverAsyncSettleAfterReceivingMultipleMessages(TestContext context) {
        Async async = context.async();
        connect(context, connection -> {

            AtomicInteger counter = new AtomicInteger(0);
            ProtonSession session = connection.session().open();
            session.receiver().setSource(MockServer.Addresses.five_messages.toString())
                .asyncHandler((d, m, settle) -> {
                    int count = counter.incrementAndGet();
                    switch (count) {
                        case 1: // Fall-through
                        case 2: // Fall-through
                        case 3: {
                            validateMessage(context, count, String.valueOf(count), m);
                            break;
                        }
                        case 4: {
                            validateMessage(context, count, String.valueOf(count), m);

                            // We only issued 4 credits, so we should not get any more messages
                            // until a previous one is settled and a credit flow issued, use the
                            // callback for this msg to do that.
                            vertx.setTimer(1000, x -> {
                                LOG.trace("Settling msg 4 and flowing more credit");
                                settle.run();
                            });

                            // Check that we haven't processed any more messages before then
                            vertx.setTimer(500, x -> {
                                LOG.trace("Checking msg 5 not received yet");
                                context.assertEquals(4, counter.get());
                            });
                            break;
                        }
                        case 5: {
                            validateMessage(context, count, String.valueOf(count), m);

                            // Got the last message, lets finish the test.
                            LOG.trace("Got msg 5, completing async");
                            async.complete();
                            connection.disconnect();
                            break;
                        }
                    }
                })
                .flow(4)
                .open();
        });
    }

    @Test(timeout = 20000)
    public void testIsAnonymousRelaySupported(TestContext context) {
        Async async = context.async();
        connect(context, connection -> {
            context.assertFalse(connection.isAnonymousRelaySupported(),
                    "Connection not yet open, so result should be false");
            connection.openHandler(x -> {
                context.assertTrue(connection.isAnonymousRelaySupported(),
                        "Connection now open, server supports relay, should be true");

                connection.disconnect();
                async.complete();
            })
            .open();
        });
    }

    @Test(timeout = 20000)
    public void testAnonymousRelayIsNotSupported(TestContext context) {
        ((ProtonServerImpl) server.getProtonServer()).setAdvertiseAnonymousRelayCapability(false);
        Async async = context.async();
        connect(context, connection -> {
            context.assertFalse(connection.isAnonymousRelaySupported(),
                    "Connection not yet open, so result should be false");
            connection.openHandler(x -> {
                context.assertFalse(connection.isAnonymousRelaySupported(),
                        "Connection now open, server does not support relay, should be false");

                connection.disconnect();
                async.complete();
            })
            .open();
        });
    }

    @Test(timeout = 20000)
    public void testDefaultAnonymousSenderSpecifiesLinkTarget(TestContext context) throws Exception {
        server.close();
        Async async = context.async();

        ProtonServer protonServer = null;
        try {
            protonServer = ProtonServer.create(vertx);
            protonServer.connectHandler((serverConnection) -> processConnectionAnonymousSenderSpecifiesLinkTarget(context, async, serverConnection));
            FutureHandler<ProtonServer, AsyncResult<ProtonServer>> handler = FutureHandler.asyncResult();
            protonServer.listen(0, handler);
            handler.get();

            ProtonClient client = ProtonClient.create(vertx);
            client.connect("localhost", protonServer.actualPort(), res -> {
                context.assertTrue(res.succeeded());

                ProtonConnection connection =  res.result();
                connection.openHandler(x -> {
                    LOG.trace("Client connection opened");

                    ProtonSender sender = connection.createSender(null);
                    // Can optionally add an openHandler or sendQueueDrainHandler
                    // to await remote sender open completing or credit to send being
                    // granted. But here we will just buffer the send immediately.
                    sender.open();
                    sender.send(tag("tag"), message("ignored", "content"));
                })
                .open();
            });

            async.awaitSuccess();
        } finally {
            if (protonServer != null) {
                protonServer.close();
            }
        }
    }

    private void processConnectionAnonymousSenderSpecifiesLinkTarget(TestContext context, Async async, ProtonConnection serverConnection) {
        serverConnection.sessionOpenHandler(session -> session.open());
        serverConnection.receiverOpenHandler(receiver -> {
            LOG.trace("Server receiver opened");
            //TODO: set the local target on link before opening it
            receiver.handler((delivery, msg) -> {
                // We got the message that was sent, complete the test
                LOG.trace("Server got msg: {0}", getMessageBody(context, msg));
                serverConnection.disconnect();
                async.complete();
            });

            // Verify that the remote link target (set by the client) matches
            // up to the expected value to signal use of the anonymous relay
            Target remoteTarget = receiver.getRemoteTarget();
            context.assertNotNull(remoteTarget, "Client did not set a link target");
            context.assertNull(remoteTarget.getAddress(), "Unexpected target address");

            receiver.flow(10).open();
        });
        serverConnection.openHandler(result -> {
            serverConnection.open();
        });
    }

    private void validateMessage(TestContext context, int count, Object expected, Message msg) {
        Object actual = getMessageBody(context, msg);
        LOG.trace("Got msg {0}, body: {1}", count, actual);

        context.assertEquals(expected, actual, "Unexpected message body");
    }

    private Object getMessageBody(TestContext context, Message msg) {
        Section body = msg.getBody();

        context.assertNotNull(body);
        context.assertTrue(body instanceof AmqpValue);

        return ((AmqpValue) body).getValue();
    }
}
