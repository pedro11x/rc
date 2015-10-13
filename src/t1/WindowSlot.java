package t1;


public class WindowSlot extends TftpPacket {
	
	private long time;
	private boolean acked;
	private int tries;
	
	
	public WindowSlot(){
		super();
		acked=false;
		tries=0;
	}
	
	
	public boolean isExpired(int timeout) {
		return System.currentTimeMillis() - time >= timeout;
		
	}
	
	public long timeout(int timeout) {
		return timeout - System.currentTimeMillis() - time;
		
	}
	
	public void setAcked() {
		 acked=true;
	}
	
	public boolean isAcked() {
		return acked;
	}
	
	public synchronized void sent(){
		time = System.currentTimeMillis();
		tries++;
	}
	
	public int getRetries(){return tries-1;}
}
