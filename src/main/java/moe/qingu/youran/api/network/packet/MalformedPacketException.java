package moe.qingu.youran.api.network.packet;

/** 报文格式不对（长度、版本、校验和等）。收到这种包一律丢掉。 */
public final class MalformedPacketException extends Exception {
    public MalformedPacketException(String message) {
        super(message, null, false, false);
    }
}
