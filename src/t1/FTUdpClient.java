package t1;

import static t1.TftpPacket.MAX_TFTP_PACKET_SIZE;
import static t1.TftpPacket.OP_ACK;
import static t1.TftpPacket.OP_DATA;
import static t1.TftpPacket.OP_WRQ;

import java.io.FileInputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

public class FTUdpClient {
	static final int DEFAULT_TIMEOUT = 3000;
	static final int DEFAULT_MAX_RETRIES = 5;
	static final int DEFAULT_BLOCKSIZE = 512; // default block size as in TFTP
												// RFC

	static int WindowSize;
	static int BlockSize = DEFAULT_BLOCKSIZE;
	static int Timeout = DEFAULT_TIMEOUT;

	private String filename;

	private DatagramSocket socket;
	private BlockingQueue<TftpPacket> receiverQueue;
	volatile private SocketAddress srvAddress;

	FTUdpClient(String filename, SocketAddress srvAddress) {
		this.filename = filename;
		this.srvAddress = srvAddress;
	}

	void sendFile() {
		try {

			//socket = new DatagramSocket();
			socket = new MyDatagramSocket();
			
			//create producer/consumer queue for ACKS
			receiverQueue = new ArrayBlockingQueue<>(1);

			//start a receiver process to feed the queue
			new Thread(() -> {
				try {
					for (;;) {
						byte[] buffer = new byte[MAX_TFTP_PACKET_SIZE];
						DatagramPacket msg = new DatagramPacket(buffer, buffer.length);
						socket.receive(msg);
						
						// update server address (it may answer to WRQ from a different port.
						srvAddress = msg.getSocketAddress();
						
						// make the packet available to sender process.
						TftpPacket pkt = new TftpPacket(msg.getData(), msg.getLength());
						receiverQueue.put(pkt);
					}
				} catch (Exception e) {
				}
			}).start();

			System.out.println("sending file: \"" + filename + "\" to server: " + srvAddress + " from local port:" + socket.getLocalPort());

			TftpPacket wrr = new TftpPacket().putShort(OP_WRQ).putString(filename).putByte(0).putString("octet").putByte(0);
			sendRetry(wrr, 0L, DEFAULT_MAX_RETRIES);

			try {

				FileInputStream f = new FileInputStream(filename);

				long byteCount = 1; // block byte count starts at 1

				// read and send blocks 
				int n;
				byte[] buffer = new byte[BlockSize];
				while ((n = f.read(buffer)) > 0) {
					TftpPacket pkt = new TftpPacket().putShort(OP_DATA).putLong(byteCount).putBytes(buffer, n);
					sendRetry(pkt, byteCount, DEFAULT_MAX_RETRIES);
					byteCount += n;
				}

				// Send an empty block to signal the end of file.
				TftpPacket pkt = new TftpPacket().putShort(OP_DATA).putLong(byteCount).putBytes(new byte[0], 0);
				sendRetry(pkt, byteCount, DEFAULT_MAX_RETRIES);

				f.close();

			} catch (Exception e) {
				System.err.println("Failed with error \n" + e.getMessage());
			}
			socket.close();
			System.out.println("Done...");
		} catch (Exception x) {
			x.printStackTrace();
		}
	}

	/*
	 * Send a block to the server, repeating until the expected ACK is received, or the number
	 * of allowed retries is exceeded.
	 */
	void sendRetry(TftpPacket blk, long expectedACK, int retries) throws Exception {
		for (int i = 0; i < retries; i++) {
			System.err.println("sending: " + blk + " expecting:" + expectedACK);
			socket.send(new DatagramPacket(blk.getPacketData(), blk.getLength(), srvAddress));
			TftpPacket ack = receiverQueue.poll(Timeout, TimeUnit.MILLISECONDS);
			System.err.println(">>>> got: " + ack);
			if (ack != null)
				if (ack.getOpcode() == OP_ACK)
					if (expectedACK <= ack.getBlockSeqN()) {
						return;
					} else {
						System.err.println("wrong ack ignored, block= " + ack.getBlockSeqN());
					}
				else {
					System.err.println("error +++ (unexpected packet)");
				}
			else
				System.err.println("timeout...");
		}
		throw new IOException("Too many retries");
	}

	public static void main(String[] args) throws Exception {
		MyDatagramSocket.init(1, 1);

		switch (args.length) {
		case 5:
			WindowSize = Integer.parseInt(args[4]);
		case 4:
			Timeout = Integer.parseInt(args[3]);
		case 3:
			BlockSize = Integer.valueOf(args[2]);
		case 2:
			break;
		default:
			System.out.printf("usage: java FTUdpClient filename servidor [blocksize [ timeout [ windowsize ]]]\n");
			System.exit(0);
		}

		String filename = args[0];

		// Preparar endereco e o porto do servidor
		String server = args[1];
		SocketAddress srvAddr = new InetSocketAddress(server, FTUdpServer.DEFAULT_PORT);

		new FTUdpClient(filename, srvAddr).sendFile();
	}

}
