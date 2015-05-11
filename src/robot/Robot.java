package robot;

import java.io.*;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.concurrent.*;

/**
 * Created by Ondřej Kratochvíl on 9.5.15.
 */
public class Robot {

    public static void main(String[] args) throws IOException {
        Connection connection;
        if (args.length == 1) {
            connection = new Connection(args[0]);
            connection.downloadFile();
        } else if (args.length == 2) {
            connection = new Connection(args[0], args[1]);
            connection.uploadFile();
        } else {
            System.out.println("Usage: Robot <hostname> for photo download, Robot <hostname> <file> for firmware upload");
        }
    }
}

/**
 * Sets up the connection with the server.
 */
class Connection {

    private final int LOCAL_PORT = 4000;    // local port from where packets will be sent
    private final int REMOTE_PORT = 4000;   // remote port where to send packets
    private final int TIMEOUT = 100;    // timeout in milliseconds

    private final InetAddress address;  // remote address
    private final DatagramSocket socket;
    private final long startTime;   // time when the connection was established (for timeout)
    private int connId = 0; // id of this connection
    private File uploadFile;    // file to upload to server

    // toDo: handle exceptions
    public Connection(String address) throws IOException {
        this.address = InetAddress.getByName(address);
        this.socket = new DatagramSocket(LOCAL_PORT);
        this.startTime = System.currentTimeMillis();
        System.out.printf("Connecting to %s:%d%n", address, REMOTE_PORT);
    }

    public Connection(String address, String file) throws IOException {
        this(address);
        this.uploadFile = new File(file);
    }

    /**
     * Sends a packet.
     *
     * @param packet
     * @return true if sending was successful
     */
    public boolean sendPacket(Packet packet) {
        try {
            packet.printPacket(PacketType.SENT);
            socket.send(packet.createPacket());
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Waits for incoming data on the socket
     *
     * @return {@link Packet} with received data
     */
    public Packet receivePacket() throws IOException {
        DatagramPacket datagramPacket = new DatagramPacket(new byte[264], 264, address, REMOTE_PORT);
        socket.receive(datagramPacket);
        Packet packet = new Packet(datagramPacket);
        packet.printPacket(PacketType.RECEIVED);
        return packet;
    }

    /**
     * Sends the initial packet, repeat up to 20 times if the response is not valid. If the response is still
     * invalid, sends a reset packet. Once a valid response is received, connId is set to received value.
     */
    public void openConnection(byte[] initialData) throws IOException {
        int retryCount = 0;
        // timeout the thread after 100 ms
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        Future future = executorService.submit(new InitialPacketReceiver());
        // repeat the initial message 20 times if the response is invalid
        do {
            sendPacket(Packet.initialPacket(initialData, address, REMOTE_PORT));
            try {
                future.get(TIMEOUT, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                e.printStackTrace();
            } catch (ExecutionException e) {
                e.printStackTrace();
            } catch (TimeoutException e) {
                if (connId == 0) {
                    System.err.println("Got incorrect response for initial packet. Sending a new one.");
                }
            }
        } while (connId == 0 && retryCount++ < 19);
        // reset the connection if the response is still invalid
        if (connId == 0) {
            sendPacket(new Packet(0, (short) 0, (short) 0, Packet.RST_FLAG, new byte[0], address, REMOTE_PORT));
        } else {
            future.cancel(true);
            executorService.shutdown();
        }
    }

    /**
     * Downloads the photo from the server and saves it to file
     */
    public void downloadFile() throws IOException { // toDo: timeout when connection is unexpectedly closed
        openConnection(Packet.DOWNLOAD);
        if (connId == 0) {
            System.err.println("Connection was not opened, RST flag was sent to the server");
        } else {
            // got valid response, start accepting photo packets
            // flag has to be 0, connId has to be the same
            System.out.print("\n\nDOWNLOADING STARTED\n\n");
            DataPacketHandler handler = new DataPacketHandler();
            Packet dataPacket;
            Packet packetToSend = null;
            do {
                if (packetToSend != null) {
                    sendPacket(packetToSend);
                }
                dataPacket = receivePacket();
                if (dataPacket.getConnId() != connId) {
                    continue;
                }
                packetToSend = handler.handlePacket(dataPacket);
            } while (dataPacket.getFlag() == Packet.EMPTY_FLAG || dataPacket.getConnId() != connId);
            if (packetToSend.getFlag() == Packet.FIN_FLAG) {
                sendPacket(packetToSend);
                System.out.print("\n\nDOWNLOADING FINISHED\n\n");
            }
            handler.closeStream();
        }
    }

    /**
     * Uploads the file with firmware to the server
     */
    public void uploadFile() throws IOException {
        openConnection(Packet.UPLOAD);
        if (connId == 0) {
            System.err.println("Connection was not opened, RST flag was sent to the server");
        } else {
            // toDo: implement
        }
    }

    /**
     * Thread that awaits packet on a socket. Implements runnable, so can be interrupted/timeouted.
     */
    private class InitialPacketReceiver implements Runnable {

        @Override
        public void run() {
            try {
                Packet packet = null;
                while (packet == null || !packet.isValidInitialResponse()) {
                    packet = receivePacket();
                }
                connId = packet.getConnId();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}

/**
 * Handles given packet according to context
 */
interface PacketHandler {

    /**
     * Handles incoming packet, returns packet that should be sent as a response
     *
     * @param packet
     * @return
     */
    Packet handlePacket(Packet packet);
}

/**
 * Handles incoming data packets and writes the data to file
 */
class DataPacketHandler implements PacketHandler {

    private final String FILENAME = "foto.png";
    private final int WINDOW_SIZE = 8;  // 8 packets containing up to 255 bytes of data = 2040
    private File file;
    private FileOutputStream fos;
    private LinkedList<byte[]> content;   // list of complete photo data
    private int written = 0;    // amount of written bytes

    public DataPacketHandler() {
        try {
            this.file = new File(FILENAME);
            this.fos = new FileOutputStream(file);
            this.content = new LinkedList<>();
            // init the data container
            shiftWindow();
        } catch (FileNotFoundException e) {
            System.err.printf("File with name %s not found.%n", FILENAME);
        }
    }

    /**
     * Handles given packet
     *
     * @param packet received packet
     * @return response packet
     */
    public Packet handlePacket(Packet packet) {
        // downloading is completed
        if (packet.getFlag() == Packet.FIN_FLAG) {
            return Packet.finPacket(packet.getConnId(), packet.getSeq(), packet.getAddress(), packet.getPort());
        }
        int seq = packet.getSeq();
        // get correct index if unsigned int overflowed (even multiple times)
        while (seq % 255 != 0) {
            seq += 0x10000;
        }
        int index = (seq - written) / 255;
        if (index >= 0 && content.get(index) == null) {
            // this packet was not yet accepted
            content.set(index, packet.getData());
        } else {
            // this packet was already accepted
        }
        writeToFile();
        shiftWindow();
        return new Packet(packet.getConnId(), (short) 0, (short) written, Packet.EMPTY_FLAG, new byte[0], packet.getAddress(), packet.getPort());
    }

    /**
     * Writes the data into a file
     *
     * @return true if writing was successful
     */
    public boolean writeToFile() {
        try {
            Iterator<byte[]> iterator = content.iterator();
            while (iterator.hasNext()) {
                byte[] data = iterator.next();
                if (data == null) {
                    break;
                } else {
                    fos.write(data);
                    written += data.length;
                    iterator.remove();
                }
            }
        } catch (IOException e) {
            System.err.println("Writing to file failed.");
            return false;
        }
        return true;
    }

    /**
     * Shifts the packet accepting window.
     */
    private void shiftWindow() {
        while (content.size() < WINDOW_SIZE) {
            content.add(null);
        }
    }

    /**
     * Properly closes the stream when all the data is written.
     *
     * @return true if closing was successful
     */
    public boolean closeStream() {
        try {
            fos.flush();
            fos.close();
        } catch (IOException e) {
            System.err.println("Cannot close stream.");
            return false;
        }
        return true;
    }
}

/**
 * Sends a file by packets to the server
 */
class FileSender {

    private final int WINDOW_SIZE = 8;  // 8 packets containing up to 255 bytes of data = 2040
    private File file;
    private FileInputStream fis;

    public FileSender(String fileName) {
        try {
            this.file = new File(fileName);
            this.fis = new FileInputStream(file);
        } catch (FileNotFoundException e) {
            System.err.printf("File %s was not found.%n", fileName);
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
    private final int seq;
    private final int ack;
    private final byte flag;
    private final byte[] data;
    private final InetAddress address;
    private final int port;

    public Packet(int connId, int seq, int ack, byte flag, byte[] data, InetAddress address, int port) {
        this.connId = connId;
        this.seq = seq;
        this.ack = ack;
        this.flag = flag;
        this.data = data;
        this.address = address;
        this.port = port;
    }

    public Packet(DatagramPacket packet) {
        ByteBuffer buffer = ByteBuffer.wrap(packet.getData());  // ByteBuffer default is Big Endian
        this.connId = buffer.getInt();
        this.seq = buffer.getShort() & 0xffff;
        this.ack = buffer.getShort() & 0xffff;
        this.flag = buffer.get();
        data = new byte[packet.getLength() - 9];    // 4 + 2 + 2 + 1 = 9 (packet header length)
        for (int i = 0; i < data.length; ++i) {
            data[i] = buffer.get();
        }
        this.address = packet.getAddress();
        this.port = packet.getPort();
    }

    public int getConnId() {
        return connId;
    }

    public int getSeq() {
        return seq;
    }

    public int getAck() {
        return ack;
    }

    public byte getFlag() {
        return flag;
    }

    public byte[] getData() {
        return data;
    }

    public InetAddress getAddress() {
        return address;
    }

    public int getPort() {
        return port;
    }

    /**
     * Create a {@link DatagramPacket} out of the data.
     *
     * @return
     */
    public DatagramPacket createPacket() throws UnknownHostException {
        ByteBuffer byteBuffer = ByteBuffer.allocate(data.length + 9);
        byteBuffer.putInt(connId);
        byteBuffer.putShort((short) (seq & 0xffff));
        byteBuffer.putShort((short) (ack & 0xffff));
        byteBuffer.put(flag);
        byteBuffer.put(data);
        DatagramPacket packet = new DatagramPacket(byteBuffer.array(), data.length + 9, address, port);
        return packet;
    }

    /**
     * Create initial packet, use {@link Packet#DOWNLOAD} or {@link Packet#UPLOAD} as an argument
     *
     * @param data
     * @param address
     * @param port
     * @return
     */
    public static Packet initialPacket(byte[] data, InetAddress address, int port) {
        return new Packet(0, (short) 0, (short) 0, SYN_FLAG, data, address, port);
    }

    /**
     * Create packet that is sent after successfully downloading a photo
     *
     * @param connId
     * @param address
     * @param port
     * @return
     */
    public static Packet finPacket(int connId, int ack, InetAddress address, int port) {
        return new Packet(connId, (short) 0, (short) ack & 0xffff, FIN_FLAG, new byte[0], address, port);
    }

    /**
     * Prints the content of the packet to stdout
     *
     * @param packetType .
     */
    public void printPacket(PacketType packetType) {
        System.out.printf(packetType.getTitle() + " connID: %x seq: %d ack: %d flag: %d data: ",
                connId, seq & 0xffff, ack & 0xffff, flag);
        for (byte b : data) {
            System.out.printf("%x ", b);
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

    /**
     * Returns true if the packet is valid data packet for given connId
     *
     * @param connId
     * @return
     */
    public boolean isValidDataPacket(int connId) {
        return flag == EMPTY_FLAG && this.connId == connId;
    }

    /**
     * Returns unsigned short value of given signed short as an integer
     *
     * @param s
     * @return
     */
    public static int toUnsignedShort(short s) {
        return s & 0xffff;
    }
}

enum PacketType {

    RECEIVED("RECV"),
    SENT("SEND");

    private final String title;

    PacketType(String title) {
        this.title = title;
    }

    public String getTitle() {
        return title;
    }
}