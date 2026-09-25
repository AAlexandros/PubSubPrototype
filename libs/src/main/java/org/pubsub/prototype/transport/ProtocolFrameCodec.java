package org.pubsub.prototype.transport;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageCodec;
import org.pubsub.prototype.protocol.ProtocolCodec;
import org.pubsub.prototype.protocol.ProtocolMessage;

import java.util.List;

/**
 * Converts JSON byte array into netty {@link ByteBuf} objects.
 * ProtocolFrameCodec the codec object that will initialy make the object-byte array conversion.
 */
final class ProtocolFrameCodec extends MessageToMessageCodec<ByteBuf, ProtocolMessage> {
    private final ProtocolCodec codec = new ProtocolCodec();

    @Override
    protected void encode(ChannelHandlerContext ctx, ProtocolMessage msg, List<Object> out) {
        byte[] bytes = codec.encode(msg);
        ByteBuf buffer = ctx.alloc().buffer(bytes.length);
        buffer.writeBytes(bytes);
        out.add(buffer);
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf msg, List<Object> out) {
        byte[] bytes = new byte[msg.readableBytes()];
        msg.readBytes(bytes);
        out.add(codec.decode(bytes));
    }
}
