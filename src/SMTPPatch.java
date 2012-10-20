import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;


/**
 * A simple SMTP proxy designed to let my company's postfix server work with Outlook 2011 for Mac.
 * 
 * This class solves the following problem:
 * 	Outlook appears to only support the LOGIN auth type.  The postfix server supports LOGIN, but only advertises PLAIN.
 * 
 * This class will intercept that particular response and append LOGIN as a possible auth type.  This will let Outlook
 * use AUTH LOGIN, which lets me use Outlook on my mac.
 * 
 * @author Jesse Rosalia
 *
 */
public class SMTPPatch {

	public static void main(String [] args) throws IOException, InterruptedException {
		ServerSocket serverSocket = null;
		final String server = args[0];
		final short port    = Short.valueOf(args[1]);
		
        try {
            serverSocket = new ServerSocket(4444);
        } catch (IOException e) {
            System.err.println("Could not listen on port: 4444.");
            System.exit(1);
        }
 
		while (true) {
	        try {
	            final Socket clientSocket = serverSocket.accept();
		        new Thread(new Runnable() {
		
					@Override
					public void run() {
						try {
							handleConnection(clientSocket, server, port);
						}
						catch (Exception e) {
							throw new RuntimeException(e);
						}
					}
		        	
		        }).start();
	        } catch (IOException e) {
	            System.err.println("Accept failed.");
	            System.exit(1);
	        }
		}	 
	}

    public static String inputStreamAsString(InputStream stream, long offset)throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        long offsetRemain = offset;
        for (long n; offsetRemain > 0 &&(n = stream.skip((long)offsetRemain)) != -1;) offsetRemain-=n;
        for (int n; stream.available() > 0 && (n = stream.read(b)) != -1;) {            
            out.write(b, 0, n);
        }
        b = null;
        return out.toString();
    }
    
    /**
     * Handle a connection from the mail client to the mail server
     * 
     * @param clientSocket
     * @param port 
     * @param server 
     * @throws IOException
     * @throws InterruptedException
     */
	private static void handleConnection(Socket clientSocket, String server, short port)
			throws IOException, InterruptedException {
		Socket relaySocket = null;
		InputStream relayIs = null;
		OutputStream relayOs = null;
		try {
			relaySocket = new Socket(server, port);
			relayIs = relaySocket.getInputStream();
			relayOs = relaySocket.getOutputStream();
		} catch (UnknownHostException e) {
			System.err.println("Don't know about host: " + server + ":" + port);
			System.exit(1);
		} catch (IOException e) {
			System.err.println("Couldn't get I/O for "
					+ "the connection to: " + server + ":" + port);
			System.exit(1);
		}

		OutputStream outOs = clientSocket.getOutputStream();
		InputStream is = clientSocket.getInputStream();
        String inputLine, outputLine;

        boolean running = true;
    	boolean ehloFound = false;
    	boolean quitRecv = false;
        while (running && clientSocket.isConnected()) {
        	//incoming from the mail client
	        if (is.available() != 0) {
	        	inputLine = inputStreamAsString(is, 0);
		        System.out.print("out: " + inputLine);
		        //check to see if we've found the ehlo..if so, we need to add in the "LOGIN" auth type
		        //NOTE: This is because our postfix server supports it along with PLAIN, but only advertises PLAIN, and outlook only supports LOGIN.
		        ehloFound = inputLine.toLowerCase().startsWith("ehlo");
		        quitRecv  = inputLine.toLowerCase().startsWith("quit");
	            relayOs.write(inputLine.getBytes()); //.println(inputLine);
	            relayOs.flush();
	        }
	        //incoming from the mail server
    		if (relayIs.available() != 0) {
    			outputLine = inputStreamAsString(relayIs, 0);
    			System.out.print("in: " + outputLine);
    			//if this is the ehlo response, add in a LOGIN auth type...it will be supported and is required for outlook to work
    			if (ehloFound) {
    				outputLine = outputLine.replaceFirst("250-AUTH PLAIN", "250-AUTH PLAIN LOGIN");
    				ehloFound = false;
    			}
    			//quit message is received, we're going to bail out after this
    			if (quitRecv) {
    				running = false;
    			}
    			outOs.write(outputLine.getBytes());
	            outOs.flush();
    		}
        	Thread.sleep(10);
        }
		relaySocket.close();
		clientSocket.close();
	}
}
