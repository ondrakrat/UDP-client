package robot;

import java.awt.color.ProfileDataException;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.concurrent.*;

/**
 * Created by Ondřej Kratochvíl on 9.5.15.
 */
public class Robot {

    public static void main(String[] args) throws IOException {
        try {
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
        } catch (PackageDeliveryException e) {
            System.err.println(e.getMessage());
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
    private String fileName;    // name of file that is to be uploaded to the server
    private int lastSeq = 0;    // seq of last sent packet
    private int sameSeqCount = 0;    // how many times in a row was a packet with the same seq number sent
    private boolean closed = false;  // is the connection still open

    // toDo: handle exceptions
    public Connection(String address) throws IOException {
        this.address = InetAddress.getByName(address);
        this.socket = new DatagramSocket(LOCAL_PORT);
        this.startTime = System.currentTimeMillis();
        System.out.printf("Connecting to %s:%d%n", address, REMOTE_PORT);
    }

    public Connection(String address, String fileName) throws IOException {
        this(address);
        this.fileName = fileName;
    }

    public int getConnId() {
        return connId;
    }

    public boolean isClosed() {
        return closed;
    }

    public synchronized void setClosed(boolean closed) {
        this.closed = closed;
    }

    /**
     * Sends a packet.
     *
     * @param packet
     * @return true if sending was successful
     */
    public boolean sendPacket(Packet packet) throws PackageDeliveryException {
        if (packet.getData().length > 0 && packet.getSeq() == lastSeq && sameSeqCount++ > 19) {
            throw new PackageDeliveryException("Sending a packet with seq " + packet.getSeq() + " 20 times in a row");
        }
        try {
            packet.printPacket(PacketType.SENT);
            DatagramPacket datgramPacket = packet.createPacket();
            datgramPacket.setAddress(address);
            datgramPacket.setPort(REMOTE_PORT);
            socket.send(datgramPacket);
            lastSeq = packet.getSeq();
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
    public void openConnection(byte[] initialData) throws IOException, PackageDeliveryException {
        int retryCount = 0;
        // timeout the thread after 100 ms
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        Future future = executorService.submit(new InitialPacketReceiver());
        // repeat the initial message 20 times if the response is invalid
        do {
            sendPacket(Packet.initialPacket(initialData));
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
            sendPacket(new Packet(0, (short) 0, (short) 0, Packet.RST_FLAG, new byte[0]));
        } else {
            future.cancel(true);
            executorService.shutdown();
        }
    }

    /**
     * Downloads the photo from the server and saves it to file
     */
    public void downloadFile() throws IOException, PackageDeliveryException { // toDo: timeout when connection is unexpectedly closed
        openConnection(Packet.DOWNLOAD);
        if (connId == 0) {
            System.err.println("Connection was not opened, RST flag was sent to the server");
        } else {
            // got valid response, start accepting photo packets
            // flag has to be 0, connId has to be the same
            System.out.print("\n\nDOWNLOADING STARTED\n\n");
            FileReceiver handler = new FileReceiver();
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
            if (packetToSend != null && packetToSend.getFlag() == Packet.FIN_FLAG) {
                sendPacket(packetToSend);
                System.out.print("\n\nDOWNLOADING FINISHED\n\n");
            }
            handler.closeStream();
        }
        socket.close();
    }

    /**
     * Uploads the file with firmware to the server
     */
    public void uploadFile() throws IOException, PackageDeliveryException {
        openConnection(Packet.UPLOAD);
        if (connId == 0) {
            System.err.println("Connection was not opened, RST flag was sent to the server");
        } else {
            FileSender sender = new FileSender(this, fileName);
            Thread windowSenderThread = sender.getTimeoutedWindowSenderThread();
            Thread receiverThread = sender.getReceiverThread();
            receiverThread.start();
            windowSenderThread.start();
            while (!closed) {
            }
        }
        socket.close();
    }


    /**
     * Thread that awaits initial packet on a socket. Implements runnable, so can be interrupted/timeouted.
     */
    private class InitialPacketReceiver implements Runnable {

        @Override
        public void run() {
            try {
                Packet packet;
                do {
                    packet = receivePacket();
                } while (!packet.isValidInitialResponse());
                connId = packet.getConnId();
            } catch (SocketException e) {
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
    Packet handlePacket(Packet packet) throws PackageDeliveryException;
}

/**
 * Handles incoming data packets and writes the data to file
 */
class FileReceiver implements PacketHandler {

    private final String FILENAME = "foto.png";
    private final int WINDOW_SIZE = 8;  // 8 packets containing up to 255 bytes of data = 2040
    private File file;
    private FileOutputStream fos;
    private LinkedList<byte[]> content;   // data in current window
    private int written = 0;    // amount of written bytes

    public FileReceiver() {
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
            return Packet.finPacket(packet.getConnId(), packet.getSeq(), Mode.DOWNLOAD);
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
        return new Packet(packet.getConnId(), (short) 0, (short) written, Packet.EMPTY_FLAG, new byte[0]);
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
class FileSender implements PacketHandler {

    private final int WINDOW_SIZE = 8;  // 8 packets containing up to 255 bytes of data = 2040
    private final int TIMEOUT = 100;    // timeout in milliseconds
    private Connection connection;
    private File file;  // file that is being sent
    private FileInputStream fis;
    private LinkedList<byte[]> content; // actual packet sending window, starting with requested packet
    private int requestedSeq = 0;   // the last packet that was requested by the server
    private long lastSent;   // time of the last sent packet
    // toDo: three times same ack behaviour

    public FileSender(Connection connection, String fileName) {
        try {
            this.connection = connection;
            this.file = new File(fileName);
            this.fis = new FileInputStream(file);
            this.content = new LinkedList<>();  // data in current window
            refillWindow();
        } catch (FileNotFoundException e) {
            System.err.printf("File %s was not found.%n", fileName);
        }
    }

    @Override
    public synchronized Packet handlePacket(Packet packet) throws PackageDeliveryException {
        if (packet.getFlag() == Packet.RST_FLAG) {
            System.err.println("\nServer has reset the connection.\n");
            closeStream();
            connection.setClosed(true);
            return null;
        }
        // connection was properly closed
        if (packet.getFlag() == Packet.FIN_FLAG) {
            System.out.print("\n\nUPLOADING FINISHED\n\n");
            closeStream();
            connection.setClosed(true);
            return null;
        }
        if (content.size() == 0) {
            connection.sendPacket(Packet.finPacket(connection.getConnId(), packet.getAck(), Mode.UPLOAD));
            return null;
        }
        int ack = packet.getAck();
        while (ack % 255 != 0) {
            ack += 0x10000;
        }
        if (ack > requestedSeq) {
            Iterator<byte[]> iterator = content.iterator();
            int diff = ack - requestedSeq;  // amount of bytes that should be removed from the window
            while (iterator.hasNext() && diff > 0) {
                diff -= iterator.next().length;
                iterator.remove();
            }
            requestedSeq = ack;
            refillWindow();
            sendWindow();
        }
        return null;
    }

    /**
     * Sends the whole window or FIN packet if the window is empty
     */
    private synchronized void sendWindow() throws PackageDeliveryException {
        if (content.size() > 0) {
            int packetSeq = requestedSeq;
            for (byte[] bytes : content) {
                connection.sendPacket(Packet.dataPacket(connection.getConnId(), packetSeq, bytes));
                packetSeq += bytes.length;
            }
        } else if (!connection.isClosed()) {
            connection.sendPacket(Packet.finPacket(connection.getConnId(), requestedSeq, Mode.UPLOAD));
        }
        lastSent = System.currentTimeMillis();
    }

    /**
     * Refill the data window so it has a maximum of {@link FileSender#WINDOW_SIZE} data packets
     *
     * @return
     */
    private synchronized boolean refillWindow() {
        int bytesRead;
        do {
            try {
                byte[] bytes = new byte[255];
                bytesRead = fis.read(bytes, 0, bytes.length);
                if (bytesRead > 0) {
                    content.add(Arrays.copyOf(bytes, bytesRead));
                }
            } catch (IOException e) {
                System.err.printf("Cannot read from the file %s.%n", file.getName());
                return false;
            }
        } while (content.size() < WINDOW_SIZE && bytesRead > 0);
        return true;
    }

    /**
     * Returns a new instance of the {@link robot.FileSender.ResponsePacketReceiver} thread
     *
     * @return
     */
    public Thread getReceiverThread() {
        return new Thread(new ResponsePacketReceiver());
    }

    /**
     * Returns a new instance of the {@link robot.FileSender.TimeoutedWindowSender} thread
     *
     * @return
     */
    public Thread getTimeoutedWindowSenderThread() {
        return new Thread(new TimeoutedWindowSender());
    }

    public long getLastSent() {
        return lastSent;
    }

    /**
     * Properly closes the stream when all the connection is closed
     *
     * @return true if closing was successful
     */
    public boolean closeStream() {
        try {
            fis.close();
        } catch (IOException e) {
            System.err.println("Cannot close stream.");
            return false;
        }
        return true;
    }

    /**
     * Thread that awaits data response packet on a socket. Implements runnable, so can be interrupted/timeouted.
     */
    private class ResponsePacketReceiver implements Runnable {

        @Override
        public void run() {
            // send the window when this thread starts
            try {
                Packet packet;
                do {
                    packet = connection.receivePacket();
                    boolean sameId = packet.getConnId() == connection.getConnId();
                    // toDo: ack should be within window size
                    if (!sameId || !packet.isValid()) {
                        connection.sendPacket(Packet.rstPacket(packet.getConnId()));
                    }
                    handlePacket(packet);
                } while (packet.getFlag() != Packet.FIN_FLAG);
            } catch (IOException e) {
                e.printStackTrace();
            } catch (PackageDeliveryException e) {
                System.err.println(e.getMessage());
            }
        }
    }

    /**
     * Sends the window if the timeout occured
     */
    private class TimeoutedWindowSender implements Runnable {

        @Override
        public void run() {
            while (!connection.isClosed()) {
                if (System.currentTimeMillis() > getLastSent() + TIMEOUT) {
                    try {
                        sendWindow();
                    } catch (PackageDeliveryException e) {
                        System.err.println(e.getMessage());
                        break;
                    }
                }
            }
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

    public Packet(int connId, int seq, int ack, byte flag, byte[] data) {
        this.connId = connId;
        this.seq = seq;
        this.ack = ack;
        this.flag = flag;
        this.data = data;
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
     * Create packet that is sent after successfully downloading a photo
     *
     * @param connId
     * @return
     */
    public static Packet finPacket(int connId, int lastSeq, Mode mode) {
        if (mode == Mode.DOWNLOAD) {
            return new Packet(connId, (short) 0, (short) lastSeq & 0xffff, FIN_FLAG, new byte[0]);
        } else if (mode == Mode.UPLOAD) {
            return new Packet(connId, (short) lastSeq & 0xffff, (short) 0, FIN_FLAG, new byte[0]);
        } else {
            throw new IllegalArgumentException("Invalid mode: " + mode);
        }
    }

    /**
     * Create a packet with file data
     *
     * @param connId
     * @param seq
     * @param data
     * @return
     */
    public static Packet dataPacket(int connId, int seq, byte[] data) {
        return new Packet(connId, seq, (short) 0, EMPTY_FLAG, data);
    }

    /**
     * Create a reset packet
     *
     * @param connId
     * @return
     */
    public static Packet rstPacket(int connId) {
        return new Packet(connId, (short) 0, (short) 0, RST_FLAG, new byte[0]);
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
     * Returns true if the packet does not have any combination of flags
     *
     * @return
     */
    public boolean hasValidFlag() {
        return flag == EMPTY_FLAG || flag == RST_FLAG || flag == FIN_FLAG || flag == SYN_FLAG;
    }

    /**
     * Returns false if the packet is invalid and the connection should be reset
     *
     * @return
     */
    public boolean isValid() {
        if (flag == SYN_FLAG && (data != DOWNLOAD || data != UPLOAD)) {
            return false;
        }
        if (flag == FIN_FLAG && data.length > 0) {
            return false;
        }
        return hasValidFlag();
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

enum Mode {
    DOWNLOAD, UPLOAD;
}

/**
 * An exception that is thrown when a package was not delivered 20 times in a row
 */
class PackageDeliveryException extends Exception {

    public PackageDeliveryException() {
    }

    public PackageDeliveryException(String message) {
        super(message);
    }

    public PackageDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }

    public PackageDeliveryException(Throwable cause) {
        super(cause);
    }
}