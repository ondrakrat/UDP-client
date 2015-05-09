package robot;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;

/**
 * Created by Ondřej Kratochvíl on 9.5.15.
 */
public class Robot {

    public static void main(String[] args) {
        if (args.length == 1) {

        } else if (args.length == 2) {

        } else {
            System.out.println("Usage: Robot <hostname> for photo download, Robot <hostname> <file> for firmware upload");
        }
    }
}

/**
 * Sets up the connection with the server.
 */
class Connection {

    private final int PORT = 4000;
    private final int TIMEOUT = 100;    // timeout in miliseconds

    private final InetAddress address;
    private final DatagramSocket socket;
    private final long startTime;   // time when the connection was established (for timeout)

    public Connection(String address) throws IOException {
        this.address = InetAddress.getByName(address);
        this.socket = new DatagramSocket();
        this.startTime = System.currentTimeMillis();
    }

    /**
     * Sends a packet.
     *
     * @param packet
     * @return true if sending was successful
     */
    public boolean sendPacket(DatagramPacket packet) {
        try {
            socket.send(packet);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Downloads the photo from the server and saves it to file
     */
    public void downloadFile() throws IOException {
        int retryCount = 0;
        Packet packet = new Packet(new DatagramPacket(new byte[264], 264));
        DatagramPacket datagramPacket = packet.createPacket();
        // repeat the initial message 20 times if the response is invalid
        // toDo: timeout
        do {
            sendPacket(Packet.initialPacket(Packet.DOWNLOAD).createPacket());
            socket.receive(datagramPacket);
        } while (!packet.isValidInitialResponse() && retryCount++ < 19);
        // reset the connection if the response is still invalid
        if (!packet.isValidInitialResponse()) {
            sendPacket(new Packet(0, (short) 0, (short) 0, Packet.RST_FLAG, new byte[0]).createPacket());
        } else {
            // got valid response, start accepting photo packets
            
        }
    }
}

/**
 * Packet with following structure:
 * <ul>
 * <li><b>4B</b> Connection id</li>
 * <li><b>2B</b> Sequence number</li>
 * <li><b>2B</b> Confirmation number</li>
 * <li><b>1B</b> Flag</li>
 * <li><b>0-255B</b> Data </li>
 * </ul>
 */
class Packet {

    public static final byte EMPTY_FLAG = 0b0000;
    public static final byte RST_FLAG = 0b0001;
    public static final byte FIN_FLAG = 0b0010;
    public static final byte SYN_FLAG = 0b0100;

    public static final byte[] DOWNLOAD = {0b0001};
    public static final byte[] UPLOAD = {0b0010};

    private final int connId;
    private final short seq;
    private final short ack;
    private final byte flag;
    private final byte[] data;

    public Packet(int connId, short seq, short ack, byte flag, byte[] data) {
        this.connId = connId;
        this.seq = seq;
        this.ack = ack;
        this.flag = flag;
        this.data = data;
    }

    public Packet(DatagramPacket packet) {
        ByteBuffer buffer = ByteBuffer.wrap(packet.getData());  // ByteBuffer default is Big Endian
        this.connId = buffer.getInt();
        this.seq = buffer.getShort();
        this.ack = buffer.getShort();
        this.flag = buffer.get();
        data = new byte[packet.getLength() - 9];    // 4 + 2 + 2 + 1 = 9 (packet header length)
        for (int i = 0; i < data.length; ++i) {
            data[i] = buffer.get();
        }
    }

    public int getConnId() {
        return connId;
    }

    public short getSeq() {
        return seq;
    }

    public short getAck() {
        return ack;
    }

    public byte getFlag() {
        return flag;
    }

    public byte[] getData() {
        return data;
    }

    /**
     * Create a {@link DatagramPacket} out of the data.
     *
     * @return
     */
    public DatagramPacket createPacket() {
        ByteBuffer byteBuffer = ByteBuffer.allocate(data.length + 9);
        byteBuffer.putInt(connId);
        byteBuffer.putShort(seq);
        byteBuffer.putShort(ack);
        byteBuffer.put(flag);
        byteBuffer.put(data);
        DatagramPacket packet = new DatagramPacket(byteBuffer.array(), data.length + 9);
        return packet;
    }

    /**
     * Create initial packet, use {@link Packet#DOWNLOAD} or {@link Packet#UPLOAD} as an argument
     *
     * @param data
     * @return
     */
    public static Packet initialPacket(byte[] data) {
        return new Packet(0, (short) 0, (short) 0, SYN_FLAG, data);
    }

    /**
     * Prints the content of the packet to stdout.
     */
    private void printPacket() {
        System.out.printf((ack == 0 ? "RECV" : "SEND") + " %d %d %x %b ", connId, seq, ack, flag);
        for (byte b : data) {
            System.out.printf("%d ", b);
        }
        System.out.println();
    }

    /**
     * Returns true if this packet is a valid response to the initial message
     *
     * @return
     */
    public boolean isValidInitialResponse() {
        return connId != 0 && flag == SYN_FLAG && seq == 0 && data.length == 1;
    }
}