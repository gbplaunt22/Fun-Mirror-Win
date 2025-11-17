package mirror;

public class Helper {
	
	//sleep helper method
	public void sleep(int milliseconds) {
		try { Thread.sleep(5000);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}
}
