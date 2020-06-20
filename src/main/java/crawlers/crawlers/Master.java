package crawlers.crawlers;

import java.net.UnknownHostException;
import java.sql.Date;
import java.sql.SQLException;
import java.util.Calendar;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;

import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

import crawlers.models.URL;
import crawlers.modules.RelativeUrlResolver;
import crawlers.modules.Seen;
import crawlers.modules.exclusion.RobotTXT;
import crawlers.modules.filter.Filter;
import crawlers.modules.frontier.selector.Selector;
import crawlers.storage.CacheService;
import crawlers.storage.URLService;
import crawlers.url.UrlLexer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Master {

	public static void main(String args[]) throws UnknownHostException, ClassNotFoundException, SQLException {
		logger.info("MASTER IS UP AND RUNNING");
		new Master().run();
	}

	private static final Logger logger = LoggerFactory.getLogger(Master.class);
	// Event to handle event in getUrlFromFrontier
	private final static String EMPTY_EVENT = "EMPTY";
	// Event to handle event in getUrlFromFrontier
	private final static String WAIT_EVENT = "WAIT";
	// Holds addresses of slaves whom ready for work
	private Queue<String> queueOfSlaves;
	// Router socket for Dealer-Router pattern
	private ZMQ.Socket ROUTER;
	// Publisher socket for Subscriber-Publisher pattern used to send heart beat to
	// master
	private ZMQ.Socket PUBLISHER;
	// to read from multiple sockets
	private ZMQ.Poller poller;
	// Time to wait before sending next heart beat
	private final static int HEARTBEAT_INTERVAL = 5000;
	// Time to send heart beat in msec
	private long nextHeartbeat;
	// event heart-beat
	private final static String HEARTBEAT_EVENT = "001";
	// event ready-for-work;
	private final static String READY_FOR_WORK_EVENT = "002";
	// event task is done
	private final static String WORK_FINISHED_EVENT = "003";
	// event task to be done
	private final static String WORK_TO_BE_DONE_EVENT = "004";
	// Address to bind-to for Dealer-Router locally
	private final static String ROUTER_ADDRESS = "tcp://127.0.0.1:5555";
	// Address to bind-to for Subscriber-Publisher locally
	private final static String PUBLISHER_ADDRESS = "tcp://*:5556";
	// Redis instance
	private CacheService cacheService;
	// Master's instance name
	private final static String REDIS_INSTNACE_NAME = "master";
	// Postgres Url service
	private URLService urlService;

	public Master() throws ClassNotFoundException, SQLException {
		queueOfSlaves = new LinkedList<String>();
		cacheService = new CacheService(REDIS_INSTNACE_NAME);
		urlService = new URLService();
	}

	//Send work to all ready slaves
	public void dispatchWork() {
		//while there is URLs in frontier give them to slaves
		while(queueOfSlaves.size() > 0 && existUrlInFrontier())
			sendWorkToThisAddress(getReadySlaveAddress());
	}
	
	public String getReadySlaveAddress(){
		logger.info("SLAVE DEQUEUED FROM THE QUEUE");
		return queueOfSlaves.remove();
	}
	
	public void sendWorkToThisAddress(String address){
		ROUTER.sendMore(address);
		//Send event work-to-be-done
		ROUTER.sendMore(WORK_TO_BE_DONE_EVENT);
		//TODO: put the body to be sent in queue then send it index to slave
		//Send body of message
		ROUTER.send("https://www.webhostface.com/blog/top-10-most-popular-websites-for-2017/");
		logger.info("WORK SENT TO SLAVE {}", address);
	}
	
	//If url exit fetch it 
	//TODO: change this when the frontier is ready
	public boolean existUrlInFrontier() {
		return true;
	}
	
	public void run() {
		try (ZContext context = new ZContext()) {
			
			  ROUTER = context.createSocket(SocketType.ROUTER);
		      PUBLISHER = context.createSocket(SocketType.PUB);
		      poller = context.createPoller(2);
		      
		      PUBLISHER.bind(PUBLISHER_ADDRESS);
		      ROUTER.bind(ROUTER_ADDRESS);
		      
		      //Register two sockets in poller so to listen on both sockets
		      poller.register(ROUTER, ZMQ.Poller.POLLIN);
		      poller.register(PUBLISHER, ZMQ.Poller.POLLIN);
		      nextHeartbeat = System.currentTimeMillis() + HEARTBEAT_INTERVAL;
		      
		      while (true) {
				poller.poll(HEARTBEAT_INTERVAL);

				//if it's time to send heart beat send it
				sendHearbeat();

		    	//If there is a URL in frontier dispatch it to available slave
				dispatchWork();
						    	
		    	//message received from slave
		    	if(poller.pollin(0)) {
		    		//The message received from slave should have three part first part is address second part is event type and third part is body content

					handleMessage(ROUTER.recvStr(), ROUTER.recvStr(), ROUTER.recvStr());
		    	}
		     }
		}
	}
	
	//Takes the event frame and take action upon it
	public void handleMessage(String frame1, String frame2, String frame3) {
		
		//logger.info("MESSAGE RECEIVED FROM SLAVE {}", frame1);

		if(frame2.equals(READY_FOR_WORK_EVENT))
			insertSlave(frame1);
		else if(frame2.equals(WORK_FINISHED_EVENT))
			handleFinishedWork(frame3);
	}
	
	//When slave sends back response that means an crawled 
	public void handleFinishedWork(String key) {
		logger.info("SLAVE FINISHED CRAWLING DOMAINNAME: {}", key);
				
		CompletableFuture.supplyAsync(() -> cacheService.get(key))
			.thenApplyAsync(doc -> UrlLexer.extractURLs(doc))
				.thenApplyAsync(urls -> RelativeUrlResolver.normalize(key, urls))
					.thenApplyAsync(urls -> Filter.drop(urls))
						.thenApplyAsync(urls -> RobotTXT.filter(key, urls))
							.thenApplyAsync(urls -> Seen.filter(urls))
								.thenAcceptAsync(urls -> urls.forEach(url -> urlService.add(new URL(url, new Date(Calendar.getInstance().getTimeInMillis()), "placeholder"))));
	}

	//Creates a new slave object for an address and enqueue it
	public void insertSlave(String address){
		queueOfSlaves.add(address);
		logger.info("SLAVE REGISTERED IN QUEUE WITH ADDRESS {}", address);
	}
	
	//This needs to be sent to alert slaves that master a live and if any new subscriber haven't pushed in queue and in idle state 
	public void sendHearbeat() {
		//It's time to send heart beat to all subscriber
		if(System.currentTimeMillis() > nextHeartbeat) {
			PUBLISHER.send(HEARTBEAT_EVENT);
			logger.info("HEARTBEAT SENT TO SLAVES");
			nextHeartbeat = System.currentTimeMillis() + HEARTBEAT_INTERVAL;
		}
	}
	
	//Call Selector class to fetch URL from frontier
	public String getUrlFromFrontier() {
		String response = Selector.select();
		return null;
	}

}