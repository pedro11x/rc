package t1;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * TftpPacket - look at a byte array as a tftp packet.
 * 
 * <p>
 * Message format according to the TFTP protocol (rfc 1350, 2347) with one
 * <p>
 * single modification: DATA and ACK packets use 8 bytes (long) instead of
 * <p>
 * 2 (short) to code the Block # since the field may be interpreted as
 * <p>
 * byte order in the file instead of block order in the file.
 * <p>
 * Note: this implementation assumes that all Java Strings used only contain
 * ASCII characters. If it's not so, length() and getBytes() return different
 * values and unexpected problems can appear ...
 * 
 * <pre>
 * tftp request packet
 *          2 bytes   string   1 byte  string   1 byte   optional options (strings)
 *          -----------------------------------------+ - - - + - + - - - + - + ->
 * RRQ/WRQ | 01/02 | Filename |  0  |   Mode   |  0  |  opt1 | 0 | value1| 0 | ...
 *          -----------------------------------------+ - - - + - + - - - + - + ->
 *       Modes (ascii chars/1 byte each):
 *               "netascii", "octet",...
 * 
 *        2 bytes    8 bytes      n bytes (Block # is 2 bytes in standard TFTP)
 *        ---------------------------------
 * DATA  | 03    |   Block #  |    Data    |
 *        ---------------------------------
 *        2 bytes    8 bytes            (Block # is 2 bytes in standard TFTP)
 *        ---------------------
 * ACK   | 04    |   Block #  |
 *        ---------------------- - - - - - - - -
 *        2 bytes   2 bytes       string    1 byte
 *        ----------------------------------------
 * ERROR | 05    |  ErrorCode |   ErrMsg   |   0  |
 *        ----------------------------------------
 *       Error Codes:
 *          0         Not defined, see error message (if any).
 *          1         File not found.
 *          2         Access violation.
 *          3         Disk full or allocation exceeded.
 *          4         Illegal TFTP operation.
 *          5         Unknown transfer ID.
 *          6         File already exists.
 *          7         No such user.
 * </pre>
 */

public class TftpPacket {
	public static final int MAX_TFTP_PACKET_SIZE = 65536;

	// Op Code
	public static final short OP_RRQ = 1;
	public static final short OP_WRQ = 2;
	public static final short OP_DATA = 3;
	public static final short OP_ACK = 4;
	public static final short OP_ERROR = 5;

	// Extensions
	protected static final short OP_OACK = 6;

	protected byte[] packet;
	protected ByteBuffer bb;

	/**
	 * Constructor for creating a new, initially, empty TftpPacket
	 * 
	 **/
	public TftpPacket() {
		bb = ByteBuffer.allocate(MAX_TFTP_PACKET_SIZE);
		packet = bb.array();
	}

	/**
	 * Constructor for decoding a byte array as a TftpPacket
	 * 
	 **/
	public TftpPacket(byte[] packet, int length) {
		this.packet = packet;
		this.bb = ByteBuffer.wrap(packet, 0, length);
		this.bb.position(length); // ensure internal bb position is at "length"
	}

	/**
	 * Gets the opcode from the first two bytes of the packet, stored in net
	 * byte order (Big Endian)
	 */
	public int getOpcode() {
		return bb.getShort(0);
	}

	/**
	 * 
	 * @return the size of the TftpPacket in bytes
	 */
	public int getLength() {
		return bb.position();
	}

	/**
	 * 
	 * @return the byte array containing the TftpPacket
	 */
	public byte[] getPacketData() {
		byte[] res = new byte[getLength()];
		System.arraycopy(packet, 0, res, 0, res.length);
		return res;
	}

	/**
	 * Assuming the TftpPacket is a RRQ or WRQ
	 * 
	 * @return the filename
	 */
	public String getFilename() {
		return new String(packet, 2, getLength() - 2).split("\0")[0];
	}

	/**
	 * Assuming the TftpPacket is a RRQ or WRQ
	 * 
	 * @return the transfer mode
	 */
	public String getMode() {
		return new String(packet, 2, getLength() - 2).split("\0")[1];
	}

	/**
	 * Assuming the TftpPacket is an ERROR
	 * 
	 * @return the error code
	 */
	public int getErrorCode() {
		return bb.getShort(2);
	}

	/**
	 * Assuming the TftpPacket is an ERROR
	 * 
	 * @return the error message
	 */
	public String getErrorMessage() {
		return new String(packet, 4, getLength() - 4);
	}

	/**
	 * Assuming the TftpPacket is a DATA or an ACK
	 * 
	 * @return the block number
	 */
	public long getBlockSeqN() {
		return bb.getLong(2);
	}

	/**
	 * Assuming the TftpPacket is a DATA
	 * 
	 * @return the byte array with the data payload
	 */
	public byte[] getBlockData() {
		final int offset = 10;
		byte[] res = new byte[getLength() - offset];
		System.arraycopy(packet, offset, res, 0, res.length);
		return res;
	}

	/**
	 * Assuming the TftpPacket is a RRQ or WRQ, or OACK, returns the options
	 * <key,value> as a map
	 * 
	 * @return the byte array with the data payload
	 */
	public Map<String, String> getOptions() {
		Map<String, String> res = new HashMap<>();
		int offset = getOpcode() == OP_OACK ? 0 : 2;
		String[] str = new String(packet, 2, getLength() - 2).split("\0");
		for (int i = offset; i + 1 < str.length; i += 2)
			res.put(str[i], str[i + 1]);

		return res;
	}

	/**
	 * Appends a byte to the TftpPacket
	 * 
	 */
	public TftpPacket putByte(int b) {
		bb.put((byte) b);
		return this;
	}

	/**
	 * Appends a short (2 bytes, in net order) to the TftpPacket
	 * 
	 */
	public TftpPacket putShort(int s) {
		bb.putShort((short) s);
		return this;
	}

	/**
	 * Appends a long (8 bytes, in net order) to the TftpPacket
	 * 
	 */
	public TftpPacket putLong(long l) {
		bb.putLong(l);
		return this;
	}

	/**
	 * Appends a string (ascii 8-bit chars) to the TftpPacket [does not include
	 * '\0' to terminate the string]
	 * 
	 */
	public TftpPacket putString(String s) {
		bb.put(s.getBytes());
		return this;
	}

	/**
	 * Appends length bytes of the given (block) byte array to the TftpPacket
	 * 
	 */
	public TftpPacket putBytes(byte[] block, int length) {
		bb.put(block, 0, length);
		return this;
	}

	public String toString() {
		StringBuilder sb = new StringBuilder();
		switch (this.getOpcode()) {
		case OP_RRQ:
			sb.append("RRQ<");
		case OP_WRQ:
			sb.append("WRQ< filename: ");
			sb.append(this.getFilename()).append(", mode: ").append(this.getMode()).append("> options: ").append(this.getOptions());
			break;
		case OP_DATA:
			sb.append("DATA<").append( this.getBlockSeqN()).append(" : ").append( this.getBlockData().length ).append(">");
			break;
		case OP_ACK:
			sb.append("ACK<").append(this.getBlockSeqN() ).append(">");
			break;
		}
		return sb.toString();
	}
}