package io.vertx.proton;

import io.vertx.core.Handler;
import org.apache.qpid.proton.message.Message;

/**
 * @author <a href="http://tfox.org">Tim Fox</a>
 */
public interface ProtonSender extends ProtonLink<ProtonSender> {

  /**
   * Send the given message.
   *
   * @param message
   *          the message to send
   * @return the delivery used to send the message
   */
  ProtonDelivery send(Message message);

  /**
   * Send the given message, registering the given handler to be called whenever the related delivery is updated due to
   * receiving disposition frames from the peer.
   *
   * @param message
   *          the message to send
   * @param onUpdated
   *          handler called when a disposition update is received for the delivery
   * @return the delivery used to send the message
   */
  ProtonDelivery send(Message message, Handler<ProtonDelivery> onUpdated);

  /**
   * Send the given message, using the supplied delivery tag when creating the delivery.
   *
   * @param tag
   *          the tag to use for the delivery used to send the message
   * @param message
   *          the message to send
   * @return the delivery used to send the message
   */
  ProtonDelivery send(byte[] tag, Message message);

  /**
   * Send the given message, using the supplied delivery tag when creating the delivery, and registering the given
   * handler to be called whenever the related delivery is updated due to receiving disposition frames from the peer.
   *
   * @param tag
   *          the tag to use for the delivery used to send the message
   * @param message
   *          the message to send
   * @param onUpdated
   *          handler called when a disposition update is received for the delivery
   * @return the delivery used to send the message
   */
  ProtonDelivery send(byte[] tag, Message message, Handler<ProtonDelivery> onUpdated);

  /**
   * Gets whether the senders outgoing send queue is full, i.e. there is currently no credit to send and send
   * operations will actually buffer locally until there is.
   *
   * @return whether the send queue is full
   */
  boolean sendQueueFull();

  /**
   * Sets a handler called when the send queue is not full, i.e. there is credit available to send messages.
   *
   * @param handler
   *          the handler to process messages
   * @return the receiver
   */
  void sendQueueDrainHandler(Handler<ProtonSender> drainHandler);

  /**
   * Sets whether sent deliveries should be automatically locally-settled once they have become remotely-settled by the
   * receiving peer.
   *
   * True by default.
   *
   * @param autoSettle
   *          whether deliveries should be auto settled locally after being settled by the receiver
   * @return the sender
   */
  ProtonSender setAutoSettle(boolean autoSettle);

  /**
   * Get whether the receiver is auto settling deliveries.
   *
   * @return whether deliveries should be auto settled locally after being settled by the receiver
   * @see #setAutoSettle(boolean)
   */
  boolean isAutoSettle();
}
