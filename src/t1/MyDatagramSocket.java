package t1;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.util.LinkedList;
import java.util.Random;

/**
 * Class used to simulate lost packets in UDP
 */
public class MyDatagramSocket extends DatagramSocket {
	private static Random rand = null;

	// ----------
	// nao esquecer de chamar uma vez init( N, M ) antes de usar
	// MyDatagramSocket()
	// por exemplo: init( 10000, 0 );

	// ----------
	// parametros que alteram o comportamento da rede
	// (os valores realmente usados serao proximos destes, mas podem nao ser
	// exactamente estes):

	private static final int _PROB_MSG_LOSS = 20; // 2% packet loss
	private static final int _IF_BANDWIDTH = 4000000; // 4 Mbps
	private static final double _PPT_JITTER = 0.10; // factor de jitter no tempo
													// de propagacao
	private static final double _PPT_AVERAGE = 5; // tempo de propagacao medio
													// em mili segundos

	// ----------
	// nao alterar daqui para baixo

	private static final double _PPT_AMPLITUDE = 0.1 * _PPT_AVERAGE;
	private static final double _PPT_PERIOD = 40000;

	private static int PROB_MSG_LOSS;
	private static int IF_BANDWIDTH;
	private static double PPT_PERIOD;
	private static double PPT_JITTER;
	private static double PPT_AVERAGE;
	private static double PPT_AMPLITUDE;

	/**
	 * Constructor. Use instead of new DatagramSocket() ;
	 */
	public MyDatagramSocket() throws SocketException {
		super();
		init();
	}

	/**
	 * Constructor. Use instead of new DatagramSocket( port ) ;
	 */
	public MyDatagramSocket(int port) throws SocketException {
		super(port);
		init();
	}

	/**
	 * Constructor. Not usually used in the context of "Redes de Computadores".
	 */
	public MyDatagramSocket(int port, InetAddress laddr) throws SocketException {
		super(port, laddr);
		init();
	}

	/**
	 * Constructor. Not usually used in the context of "Redes de Computadores".
	 */
	public MyDatagramSocket(SocketAddress bindaddr) throws SocketException {
		super(bindaddr);
		init();
	}

	/**
	 * Redefinition of the send() method of DatagramSocket. The single
	 * difference in behavior is that before returning, it waits for the
	 * transmission time of the datagram (header + data).
	 */
	public void send(DatagramPacket p) throws IOException {
		if (!dropIt())
			send_and_fake_delays(p);
		else
			System.err.println("myDS: Falha no envio do pacote");
	}

	/**
	 * Redefinition of the receive() method of DatagramSocket.
	 */
	public void receive(DatagramPacket p) throws IOException {
		boolean first = true;
		int len = p.getLength();
		do {
			if (first)
				first = false;
			else
				System.err.println("myDS: Falha na recepcao do pacote");

			p.setLength(len);
			super.receive(p);
		} while (dropIt());
	}

	private static boolean dropIt() {
		return rand.nextInt(1000) + 1 <= PROB_MSG_LOSS;
	}

	private void fake_TransmissionDelay(int length) {
		int t = (int) (1e3 * (length + 28) * 8 / IF_BANDWIDTH);
		try {
			Thread.sleep(t);
		} catch (Exception x) {
		}
	}

	private long now() {
		return System.currentTimeMillis();
	}

	private void send_and_fake_delays(DatagramPacket packet) {
		fake_TransmissionDelay(packet.getLength());

		double t = 2 * Math.PI * now();
		double ptt = PPT_AVERAGE + PPT_AMPLITUDE * (0.5 * Math.sin(t / PPT_PERIOD) + PPT_JITTER * rand.nextDouble());
		long due = now() + (int) ptt;

		synchronized (queue) {
			if (queue.isEmpty())
				queue.notify();
			queue.addLast(new Packet(due, packet));
		}
	}

	synchronized private void init() {
		if (rand == null)
			throw new RuntimeException("ERROR: " + this.getClass() + " not yet initialized. Method init(profile,seed) has to be used before first use.");

		Thread t = new Thread() {
			public void run() {
				for (;;) {
					synchronized (queue) {
						try {
							while (queue.isEmpty())
								queue.wait();
							Packet p = queue.getFirst();

							queue.wait(Math.max(1, p.due - now()));
							MyDatagramSocket.super.send(p.packet);
							queue.removeFirst();
						} catch (Exception x) {
							x.printStackTrace();
						}
					}
				}
			}
		};
		t.setDaemon(true);
		t.start();
	}

	/**
	 * Prepares this class for use. This method must be called, only once,
	 * before instances can be created.
	 * 
	 * This method configures the internal parameters used in this simple
	 * simulation of the behavior of the UDP protocol in a large scale network.
	 * 
	 * @param profile
	 *            Defines the network profile: packet loss, bandwidth, RTT, etc.
	 *            Use 0 to disable random initialization.
	 * @param seed
	 *            Initializes the internal random number generator used in the
	 *            network simulation. Use 0 in the final version of the code.
	 *            For debugging purposes, other values will produce a fixed,
	 *            deterministic sequence of numbers allowing to recreate the
	 *            same behavior repeatedly.
	 */
	public static void init(int profile, int seed) {
		if (rand != null)
			throw new RuntimeException("ERROR: MyDatagramSocket already inicialized. Call init() just once.");

		rand = new Random(seed);

		if (profile == 0) {
			PROB_MSG_LOSS = _PROB_MSG_LOSS;
			IF_BANDWIDTH = _IF_BANDWIDTH;
			PPT_AVERAGE = _PPT_AVERAGE;
			PPT_PERIOD = _PPT_PERIOD;
			PPT_JITTER = _PPT_JITTER;
			PPT_AMPLITUDE = _PPT_AMPLITUDE;
		} else {
			Random rg = new Random(profile);
			PROB_MSG_LOSS = _PROB_MSG_LOSS + rg.nextInt((int) Math.round(0.3 * _PROB_MSG_LOSS));
			IF_BANDWIDTH = _IF_BANDWIDTH + rg.nextInt((int) Math.round(0.5 * _IF_BANDWIDTH));
			PPT_AVERAGE = _PPT_AVERAGE + 0.5 * rg.nextInt((int) Math.round(_PPT_AVERAGE));
			PPT_PERIOD = _PPT_PERIOD + 0.3 * rg.nextInt((int) Math.round(_PPT_PERIOD));
			PPT_JITTER = _PPT_JITTER + 0.3 * rg.nextInt((int) Math.round(_PPT_JITTER * 10)) / 10;
			PPT_AMPLITUDE = _PPT_AMPLITUDE + 0.3 * rg.nextInt((int) Math.round(_PPT_AMPLITUDE));

		}
		System.out.println("myDS: " + IF_BANDWIDTH / 1000 + "Kbit/s Tp= " + PPT_AVERAGE + "ms, " + PROB_MSG_LOSS / 10 + "% loss");
	}

	class Packet {
		long due;
		DatagramPacket packet;
		Packet(long due, DatagramPacket p) {
			this.due = due;
			this.packet = new DatagramPacket(p.getData().clone(), p.getLength(), p.getAddress(), p.getPort());
		}
	}

	private LinkedList<Packet> queue = new LinkedList<Packet>();
}
