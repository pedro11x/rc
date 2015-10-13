package t1;

/**
 * TftpServer - a very simple TFTP like server - RC FCT/UNL
 * 
 * Limitations:
 * 		default port is not 69;
 * 		ignores mode (always works as octet (binary));
 * 		only receives files
 **/

import static t1.TftpPacket.MAX_TFTP_PACKET_SIZE;
import static t1.TftpPacket.OP_ACK;
import static t1.TftpPacket.OP_DATA;
import static t1.TftpPacket.OP_ERROR;
import static t1.TftpPacket.OP_WRQ;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;

public class FTUdpServer implements Runnable {
	public static final int DEFAULT_PORT = 10512; // my default port

	static final String[] ACCEPTED_OPTIONS = new String[] { "selective_repeat", "blksize" };
	static final int DEFAULT_BLOCKSIZE = 512;
	static final int DEFAULT_WINDOW_SIZE = 30;

	private static final int DEFAULT_TRANSFER_TIMEOUT = 15000;

	private final String filename;
	private final SocketAddress cltAddr;
	private final boolean selectiveRepeat;

	private final int blockSize;
	private final int windowSize;
	private SortedSet<Long> window;

	FTUdpServer(int windowSize, TftpPacket req, SocketAddress cltAddr) {
		this.cltAddr = cltAddr;
		this.windowSize = windowSize;

		Map<String, String> options = req.getOptions();

		if (options.containsKey("selective_repeat"))
			this.selectiveRepeat = Boolean.valueOf(options.get("selective_repeat"));
		else
			this.selectiveRepeat = false;

		if (options.containsKey("blksize"))
			this.blockSize = Integer.valueOf(options.get("blksize"));
		else
			this.blockSize = DEFAULT_BLOCKSIZE;

		filename = req.getFilename();
	}

	public void run() {
		System.out.println("START!");
		System.err.println("receiving file:" + filename + " selective-repeat: " + selectiveRepeat);
		
		if (selectiveRepeat)
			selectiveRepeatReceive();
		else
			stopAndWaitReceive();
		System.out.println("DONE!");
	}

	/*
	 * Receive a file using the S/W protocol
	 */
	private void stopAndWaitReceive() {
		try {
			//DatagramSocket socket = new DatagramSocket();
			DatagramSocket socket = new MyDatagramSocket();

			// Defines the timeout to to end the server, in case the client
			// stops sending data
			socket.setSoTimeout(DEFAULT_TRANSFER_TIMEOUT);

			// confirms the file transfer request
			sendAck(socket, 0L, cltAddr);

			// next block in sequence
			long nextBlockByte = 1L, lastInOrder = 0L;
			boolean receivedLastBlock = false;

			RandomAccessFile raf = new RandomAccessFile(filename + ".bak", "rw");

			while (!receivedLastBlock) {

				byte[] buffer = new byte[MAX_TFTP_PACKET_SIZE];
				DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);
				socket.receive(datagram);

				TftpPacket pkt = new TftpPacket(datagram.getData(), datagram.getLength());
				switch (pkt.getOpcode()) {
				case OP_DATA:

					long seqN = pkt.getBlockSeqN();
					receivedLastBlock |= pkt.getBlockData().length == 0;

					if (seqN == nextBlockByte) {
						// new data, save it at the proper offset
						byte[] data = pkt.getBlockData();
						raf.seek(pkt.getBlockSeqN() - 1L);
						raf.write(data);
						lastInOrder = seqN;
						nextBlockByte += pkt.getBlockData().length;
					}
                    sendAck(socket, lastInOrder, cltAddr);

					break;
				case OP_WRQ:
					sendAck(socket, 0L, cltAddr);
					break;
				default:
					throw new RuntimeException("Error receiving file." + filename + "/" + pkt.getOpcode());
				}
			}
			raf.close();

		} catch (SocketTimeoutException x) {
			System.err.printf("Interrupted transfer. No data received after %s ms\n", DEFAULT_TRANSFER_TIMEOUT);
		} catch (Exception x) {
			System.err.println("Receive failed: " + x.getMessage());
		}
	}

	/*
	 * Receive a file using the SR protocol
	 */
	private void selectiveRepeatReceive() {
		try {
			window = new TreeSet<Long>();
			//TODO
			DatagramSocket socket = new DatagramSocket();
			//DatagramSocket socket = new MyDatagramSocket();

			// Defines the timeout to to end the server, in case the client
			// stops sending data
			socket.setSoTimeout(DEFAULT_TRANSFER_TIMEOUT);

			// confirms the file transfer request
			sendAck(socket, 0L, cltAddr);

			// next block in sequence
			long nextBlockByte = 1L;
			boolean receivedLastBlock = false;

			RandomAccessFile raf = new RandomAccessFile(filename + ".bak", "rw");

			while (!receivedLastBlock || window.size() > 0) {
				byte[] buffer = new byte[MAX_TFTP_PACKET_SIZE];
				DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);
				socket.receive(datagram);

				TftpPacket pkt = new TftpPacket(datagram.getData(), datagram.getLength());
				switch (pkt.getOpcode()) {
				case OP_DATA:
					long seqN = pkt.getBlockSeqN();
					receivedLastBlock |= (pkt.getBlockData().length == 0);

					if (seqN > (nextBlockByte + windowSize * blockSize)) {
						System.err.println("ignoring...");
						// too large, ignore.
						continue;
					}
					
					sendAck(socket, seqN, cltAddr, "normal");

					if (seqN >= nextBlockByte && window.add(seqN)) {
						// new data, save it at the proper offset
						byte[] data = pkt.getBlockData();
						raf.seek(pkt.getBlockSeqN() - 1L);
						raf.write(data);
					}

					System.err.println( window.stream().map( v -> v / blockSize).collect( Collectors.toList()));
					// try to slide window
					while (window.size() > 0 && window.first() == nextBlockByte) {
						window.remove(window.first());
						nextBlockByte += blockSize;
					}
					
					break;
				case OP_WRQ:
					sendAck(socket, 0L, cltAddr);
					break;
				default:
					throw new RuntimeException("Error receiving file." + filename + "/" + pkt.getOpcode());
				}
			}
			raf.close();

		} catch (SocketTimeoutException x) {
			System.err.printf("Interrupted transfer. No data received after %s ms\n", DEFAULT_TRANSFER_TIMEOUT);
		} catch (Exception x) {
			System.err.println("Receive failed: " + x.getMessage());
		}
	}

	/*
	 * Prepare and send an TftpPacket ACK
	 */
	private static void sendAck(DatagramSocket s, long seqN, SocketAddress dst, String... debugMessages) throws IOException {
		TftpPacket ack = new TftpPacket().putShort(OP_ACK).putLong(seqN);
		s.send(new DatagramPacket(ack.getPacketData(), ack.getLength(), dst));
		if (debugMessages.length > 0)
			System.err.printf("sent: %s %s\n", ack, debugMessages[0]);
		else
			System.err.printf("sent: %s \n", ack);

	}

	public static void main(String[] args) throws Exception {
		MyDatagramSocket.init(1, 1);
		int port = DEFAULT_PORT;
		int windowSize = DEFAULT_WINDOW_SIZE;

		switch (args.length) {
		case 1:
			windowSize = Integer.valueOf(args[0]);
		case 0:
			break;
		default:
			System.err.println("usage: java TftpServer [window_size]");
			System.exit(0);
		}

		// create and bind socket to port for receiving client requests
		DatagramSocket mainSocket = new DatagramSocket(port);
		System.out.println("New tftp server started at local port " + mainSocket.getLocalPort());

		for (;;) { // infinite processing loop...
			try {
				// receives request from clients
				byte[] buffer = new byte[MAX_TFTP_PACKET_SIZE];
				DatagramPacket msg = new DatagramPacket(buffer, buffer.length);
				mainSocket.receive(msg);

				// look at datagram as a TFTP packet
				TftpPacket req = new TftpPacket(msg.getData(), msg.getLength());
				switch (req.getOpcode()) {
				case OP_WRQ: // Write Request
					System.err.println("Write Request:" + req.getFilename());

					// Launch a dedicated thread to handle the client
					// request...
					new Thread(new FTUdpServer(windowSize, req, msg.getSocketAddress())).start();
					break;
				default: // unexpected packet op code!
					System.err.printf("???? packet opcode %d ignored\n", req.getOpcode());
					sendError(mainSocket, 0, "Unknown request type..." + req.getOpcode(), msg.getSocketAddress());
				}
			} catch (Exception x) {
				x.printStackTrace();
			}
		}
	}

	/*
	 * Sends an error packet
	 */
	private static void sendError(DatagramSocket s, int err, String str, SocketAddress dstAddr) throws IOException {
		TftpPacket pkt = new TftpPacket().putShort(OP_ERROR).putShort(err).putString(str).putByte(0);
		s.send(new DatagramPacket(pkt.getPacketData(), pkt.getLength(), dstAddr));
	}

}
