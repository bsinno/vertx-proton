/**
 * Copyright 2015 Red Hat, Inc.
 */
package io.vertx.proton.impl;

import static io.vertx.proton.ProtonHelper.future;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.qpid.proton.Proton;
import org.apache.qpid.proton.amqp.Symbol;
import org.apache.qpid.proton.amqp.messaging.Accepted;
import org.apache.qpid.proton.amqp.messaging.Modified;
import org.apache.qpid.proton.amqp.messaging.Rejected;
import org.apache.qpid.proton.amqp.messaging.Released;
import org.apache.qpid.proton.amqp.messaging.Source;
import org.apache.qpid.proton.amqp.messaging.Target;
import org.apache.qpid.proton.amqp.transport.ErrorCondition;
import org.apache.qpid.proton.engine.Connection;
import org.apache.qpid.proton.engine.EndpointState;
import org.apache.qpid.proton.engine.Link;
import org.apache.qpid.proton.engine.Receiver;
import org.apache.qpid.proton.engine.Sender;
import org.apache.qpid.proton.engine.Session;

import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetSocket;
import io.vertx.proton.ProtonConnection;
import io.vertx.proton.ProtonReceiver;
import io.vertx.proton.ProtonSender;
import io.vertx.proton.ProtonSession;

/**
 * @author <a href="http://tfox.org">Tim Fox</a>
 * @author <a href="http://hiramchirino.com">Hiram Chirino</a>
 */
public class ProtonConnectionImpl implements ProtonConnection {
    public static final Symbol ANONYMOUS_RELAY = Symbol.valueOf("ANONYMOUS-RELAY");

    private final Connection connection = Proton.connection();
    private final Vertx vertx;
    private ProtonTransport transport;

    private Handler<AsyncResult<ProtonConnection>> openHandler;
    private Handler<AsyncResult<ProtonConnection>> closeHandler;
    private Handler<ProtonConnection> disconnectHandler;

    private Handler<ProtonSession> sessionOpenHandler = (session) -> {
        session.setCondition(new ErrorCondition(Symbol.getSymbol("Not Supported"), ""));
    };
    private Handler<ProtonSender> senderOpenHandler = (sender) -> {
        sender.setCondition(new ErrorCondition(Symbol.getSymbol("Not Supported"), ""));
    };
    private Handler<ProtonReceiver> receiverOpenHandler = (receiver) -> {
        receiver.setCondition(new ErrorCondition(Symbol.getSymbol("Not Supported"), ""));
    };
    private boolean anonymousRelaySupported;
    private ProtonSession defaultSession;
    private ProtonSender defaultSender;


    ProtonConnectionImpl(Vertx vertx, String hostname) {
        this.vertx = vertx;
        this.connection.setContext(this);
        this.connection.setContainer("vert.x-"+UUID.randomUUID());
        this.connection.setHostname(hostname);
    }

    /////////////////////////////////////////////////////////////////////////////
    //
    // Delegated state tracking
    //
    /////////////////////////////////////////////////////////////////////////////
    public ProtonConnectionImpl setProperties(Map<Symbol, Object> properties) {
        connection.setProperties(properties);
        return this;
    }

    public ProtonConnectionImpl setOfferedCapabilities(Symbol[] capabilities) {
        connection.setOfferedCapabilities(capabilities);
        return this;
    }

    @Override
    public ProtonConnectionImpl setHostname(String hostname) {
        connection.setHostname(hostname);
        return this;
    }

    public ProtonConnectionImpl setDesiredCapabilities(Symbol[] capabilities) {
        connection.setDesiredCapabilities(capabilities);
        return this;
    }

    @Override
    public ProtonConnectionImpl setContainer(String container) {
        connection.setContainer(container);
        return this;
    }

    @Override
    public ProtonConnectionImpl setCondition(ErrorCondition condition) {
        connection.setCondition(condition);
        return this;
    }

    @Override
    public ErrorCondition getCondition() {
        return connection.getCondition();
    }

    @Override
    public String getContainer() {
        return connection.getContainer();
    }

    @Override
    public String getHostname() {
        return connection.getHostname();
    }

    public EndpointState getLocalState() {
        return connection.getLocalState();
    }

    @Override
    public ErrorCondition getRemoteCondition() {
        return connection.getRemoteCondition();
    }

    @Override
    public String getRemoteContainer() {
        return connection.getRemoteContainer();
    }

    public Symbol[] getRemoteDesiredCapabilities() {
        return connection.getRemoteDesiredCapabilities();
    }

    @Override
    public String getRemoteHostname() {
        return connection.getRemoteHostname();
    }

    public Symbol[] getRemoteOfferedCapabilities() {
        return connection.getRemoteOfferedCapabilities();
    }

    public Map<Symbol, Object> getRemoteProperties() {
        return connection.getRemoteProperties();
    }

    public EndpointState getRemoteState() {
        return connection.getRemoteState();
    }

    @Override
    public boolean isAnonymousRelaySupported() {
        return anonymousRelaySupported;
    };

    /////////////////////////////////////////////////////////////////////////////
    //
    // Handle/Trigger connection level state changes
    //
    /////////////////////////////////////////////////////////////////////////////


    @Override
    public ProtonConnection open() {
        connection.open();
        flush();
        return this;
    }


    @Override
    public ProtonConnection close() {
        connection.close();
        flush();
        return this;
    }

    @Override
    public ProtonSessionImpl session() {
        return new ProtonSessionImpl(connection.session());
    }

    private ProtonSession getDefaultSession() {
        if( defaultSession == null ) {
            defaultSession = new ProtonSessionImpl(connection.session());
            //TODO: add a default close/error handler?
            defaultSession.open();
        }
        return defaultSession;
    }

    @Override
    public ProtonSender createSender(String address) {
        //TODO: move most of this into the session for reuse.

        //TODO: add a default close/error handler?
        ProtonSender sender = getDefaultSession().sender();

        Symbol[] outcomes = new Symbol[]{ Accepted.DESCRIPTOR_SYMBOL, Rejected.DESCRIPTOR_SYMBOL, Released.DESCRIPTOR_SYMBOL, Modified.DESCRIPTOR_SYMBOL};
        Source source = new Source();
        source.setOutcomes(outcomes);

        Target target = new Target();
        target.setAddress(address);

        sender.setSource(source);
        sender.setTarget(target);

        return sender;
    }

    @Override
    public ProtonReceiver receiver(String name) {
        return getDefaultSession().receiver(name);
    }

    @Override
    public ProtonReceiver receiver() {
        return getDefaultSession().receiver();
    }

    public void flush() {
        if (transport != null) {
            transport.flush();
        }
    }

    @Override
    public void disconnect() {
        if (transport != null) {
            transport.disconnect();
        }
    }

    @Override
    public boolean isDisconnected() {
        return transport==null;
    }

    @Override
    public ProtonConnection openHandler(Handler<AsyncResult<ProtonConnection>> openHandler) {
        this.openHandler = openHandler;
        return this;
    }
    @Override
    public ProtonConnection closeHandler(Handler<AsyncResult<ProtonConnection>> closeHandler) {
        this.closeHandler = closeHandler;
        return this;
    }

    @Override
    public ProtonConnection disconnectHandler(Handler<ProtonConnection> disconnectHandler) {
        this.disconnectHandler = disconnectHandler;
        return this;
    }

    @Override
    public ProtonConnection sessionOpenHandler(Handler<ProtonSession> remoteSessionOpenHandler) {
        this.sessionOpenHandler = remoteSessionOpenHandler;
        return this;
    }

    @Override
    public ProtonConnection senderOpenHandler(Handler<ProtonSender> remoteSenderOpenHandler) {
        this.senderOpenHandler = remoteSenderOpenHandler;
        return this;
    }

    @Override
    public ProtonConnection receiverOpenHandler(Handler<ProtonReceiver> remoteReceiverOpenHandler) {
        this.receiverOpenHandler = remoteReceiverOpenHandler;
        return this;
    }

    /////////////////////////////////////////////////////////////////////////////
    //
    // Implementation details hidden from public api.
    //
    /////////////////////////////////////////////////////////////////////////////

    private void processCapabilities() {
        Symbol[] capabilities = getRemoteOfferedCapabilities();
        if (capabilities != null) {
            List<Symbol> list = Arrays.asList(capabilities);
            if (list.contains(ANONYMOUS_RELAY)) {
                anonymousRelaySupported = true;
            }
        }
    }

    void fireRemoteOpen() {
        processCapabilities();

        if( openHandler !=null ) {
            openHandler.handle(future(this, getRemoteCondition()));
        }
    }

    void fireRemoteClose() {
        if( closeHandler !=null ) {
            closeHandler.handle(future(this, getRemoteCondition()));
        }
    }

    public void fireDisconnect() {
        transport = null;
        if( disconnectHandler !=null ) {
            disconnectHandler.handle(this);
        }
    }

    void bind(NetClient client, NetSocket socket) {
        transport = new ProtonTransport(connection, vertx, client, socket);
    }

    void bind(NetSocket socket) {
        bind(null, socket);
    }

    void fireRemoteSessionOpen(Session session) {
        if( sessionOpenHandler !=null ) {
            sessionOpenHandler.handle(new ProtonSessionImpl(session));
        }
    }

    void fireRemoteLinkOpen(Link link) {
        if( link instanceof Sender ) {
            if( senderOpenHandler !=null ) {
                senderOpenHandler.handle(new ProtonSenderImpl((Sender) link));
            }
        } else {
            if( receiverOpenHandler !=null ) {
                receiverOpenHandler.handle(new ProtonReceiverImpl((Receiver) link));
            }
        }
    }

}
