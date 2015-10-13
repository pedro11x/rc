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
import java.util.TreeMap;//////////////////////////////////////////////////////////////////

public class FTUdpClient {
	static final int DEFAULT_TIMEOUT = 3000;
	static final int DEFAULT_MAX_RETRIES = 5;
	static final int DEFAULT_BLOCKSIZE = 512; //DEFAULT_BLOCKSIZE = 512; // default block size as in TFTP
	static final int DEFAULT_WINDOWSIZE = 30;// RFC

	static int WindowSize = DEFAULT_WINDOWSIZE;
	static int BlockSize = DEFAULT_BLOCKSIZE;
	static int Timeout = DEFAULT_TIMEOUT;

	private String filename;

	private DatagramSocket socket;
	private BlockingQueue<TftpPacket> receiverQueue;
	private TreeMap<Long,WindowSlot> window;
	volatile private SocketAddress srvAddress;

	FTUdpClient(String filename, SocketAddress srvAddress) {
		this.filename = filename;
		this.srvAddress = srvAddress;
	}

	
	
	
	void sendFile_SR() {
		try {
			//TODO
			socket = new DatagramSocket();
			//socket = new MyDatagramSocket();

			//create producer/consumer queue for ACKs
			receiverQueue = new ArrayBlockingQueue<>(WindowSize);

			window=new TreeMap<Long,WindowSlot>();

			//start a receiver process to feed the queue
			new Thread(() -> {
				try {
					for (;;) {
						byte[] buffer = new byte[MAX_TFTP_PACKET_SIZE];
						DatagramPacket msg = new DatagramPacket(buffer, buffer.length);
						socket.receive(msg);

						// update server address (it may change due to WRQ coming from a different port
						srvAddress = msg.getSocketAddress();

						// make the packet available to sender process
						TftpPacket pkt = new TftpPacket(msg.getData(), msg.getLength());
						receiverQueue.put(pkt);
					}
				} catch (Exception e) {
				}
			}).start();

			System.out.println("sending file (SR): \"" + filename + "\" to server (SR): " + srvAddress + " from local port:" + socket.getLocalPort());

			TftpPacket wrr = new TftpPacket().putShort(OP_WRQ).putString(filename).putByte(0).putString("octet").putByte(0)
					.putString("selective_repeat").putByte(0).putString("true").putByte(0);
			sendRetry(wrr, 0L, DEFAULT_MAX_RETRIES);

			try {

				FileInputStream f = new FileInputStream(filename);

				long byteCount = 1; // block byte count starts at 1

				// read and send blocks 
				int n=0;
				byte[] buffer = new byte[BlockSize];
				
				long nextTimeout = Timeout;
				
				boolean finished=false;
				while(!finished){
					
					while (window.size() < WindowSize && (n = f.read(buffer)) > 0) {
						
						WindowSlot s = new WindowSlot();
						s.putShort(OP_DATA).putLong(byteCount).putBytes(buffer, n);
						
						window.put(byteCount, s);
						//System.out.println("Sending block "+byteCount);
						sendSlot(s);
						byteCount += n;
					}
					
					
					
//					while(!receiverQueue.isEmpty()){
//						TftpPacket ack = receiverQueue.take();
//						if (ack != null)
//							if (ack.getOpcode() == OP_ACK){
//								long sn = ack.getBlockSeqN();
//								if (window.get(sn) != null) {
//									window.get(sn).setAcked();
//									System.out.println("Block "+sn+" acked!");
//								} else {
//									System.err.println("wrong ack ignored, block= " + ack.getBlockSeqN());
//								}
//							}
//							else {
//								System.err.println("error +++ (unexpected packet)");
//							}
//					}
					
					//System.out.println("next timeout "+nextTimeout);
					do{
						long start = System.currentTimeMillis();
						TftpPacket ack = receiverQueue.poll(nextTimeout, TimeUnit.MILLISECONDS);
						if (ack != null){
							if (ack.getOpcode() == OP_ACK){
								long sn = ack.getBlockSeqN();
								if (window.get(sn) != null) {
									window.get(sn).setAcked();
									//System.out.println("Block "+sn+" acked!");
								}
							}
							else {
								//System.err.println("error +++ (unexpected packet)");
							}
						}
						else
							break;
						nextTimeout = nextTimeout - (System.currentTimeMillis() - start);
					}while(nextTimeout>0 && !receiverQueue.isEmpty());
					
					//System.out.println(receiverQueue.size());
					//System.out.println("next timeout "+nextTimeout);
					nextTimeout = Timeout;
					
					for(WindowSlot s: window.values()){
						if(s.getRetries()>DEFAULT_MAX_RETRIES){
							throw new Exception("Maximum retries reached.");
						}
						if(!s.isAcked() && s.isExpired(Timeout)){
							sendSlot(s);
							//System.out.println("RESending block");
						}
						if(s.timeout(Timeout)<nextTimeout)nextTimeout=s.timeout(Timeout);
					}
					
					while(!window.isEmpty() && window.firstEntry().getValue().isAcked()){
						window.remove(window.firstKey());
					}
					
					if(n<0 && window.isEmpty())finished=true;
					
				}
				
				// Send an empty block to signal the end of file.
				TftpPacket pkt = new TftpPacket().putShort(OP_DATA).putLong(byteCount).putBytes(new byte[0], 0);
				sendRetry(pkt, byteCount, DEFAULT_MAX_RETRIES);

				f.close();

			} catch (Exception e) {
				System.err.println("Fatal error \n" + e.getMessage());
				e.printStackTrace();
			}
			socket.close();
			System.out.println("Done...");
		} catch (Exception x) {
			x.printStackTrace();
		}
	} 


	void moveToWindow(byte[] buffer, long byteCount, int n){
		if(window.size()<WindowSize){
			WindowSlot pkt = (WindowSlot) new TftpPacket().putShort(OP_DATA).putLong(byteCount).putBytes(buffer, n);
			window.put(pkt.getBlockSeqN(), pkt);
		}
		else if (window.firstEntry().getValue().isAcked()){
			window.remove(window.firstEntry().getKey());
			WindowSlot pkt = (WindowSlot) new TftpPacket().putShort(OP_DATA).putLong(byteCount).putBytes(buffer, n);
			window.put(pkt.getBlockSeqN(), pkt);
		}
	}

	/*
	 * Send a block to the server.
	 */
	void sendSlot(WindowSlot blk) throws IOException{
		//System.err.println("sending: " );
		socket.send(new DatagramPacket(blk.getPacketData(), blk.getLength(), srvAddress));
		blk.sent();
	}
	
	
	
	void sendFile() {
		try {

			//socket = new DatagramSocket();
			socket = new MyDatagramSocket();

			//create producer/consumer queue for ACKs
			receiverQueue = new ArrayBlockingQueue<>(1);

			//start a receiver process to feed the queue
			new Thread(() -> {
				try {
					for (;;) {
						byte[] buffer = new byte[MAX_TFTP_PACKET_SIZE];
						DatagramPacket msg = new DatagramPacket(buffer, buffer.length);
						socket.receive(msg);

						// update server address (it may change due to WRQ coming from a different port
						srvAddress = msg.getSocketAddress();


						// make the packet available to sender process
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
		long start = System.currentTimeMillis();
		new FTUdpClient(filename, srvAddr).sendFile_SR();
		System.out.println("Finished in "+(System.currentTimeMillis()-start)/1000f);
	}

}
