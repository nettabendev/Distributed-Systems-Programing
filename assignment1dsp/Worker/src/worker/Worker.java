package worker;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import com.amazonaws.services.sqs.model.Message;
import com.amazonaws.services.sqs.model.ReceiveMessageRequest;
import com.amazonaws.services.sqs.model.SendMessageRequest;
import com.asprise.ocr.Ocr;

public class Worker {
	
	private static AmazonSQS sqs;
	public static void main(String args[]) {

		initState();
		
		String managerToWorkerUrl = args[0];
		String workerToManagerUrl = args[1];
		ReceiveMessageRequest req = new ReceiveMessageRequest(managerToWorkerUrl).withMaxNumberOfMessages(10).withVisibilityTimeout(300);
			List<Message> messages = sqs.receiveMessage(req).getMessages();
			while(true) {

				for(Message msg : messages) {
					//download image and save it's ending for the OCR algorithm
					String ending = downloadFile(msg.getBody());
					System.out.println("ending: "+ending);
					if(ending.split(" ")[0].equals("file")) {
						//if file not found return only url
						try {
							sendMessage(workerToManagerUrl,msg.getBody()+" "+ending, "workerToManagerQueue", msg.getBody());
							sqs.deleteMessage(managerToWorkerUrl, msg.getReceiptHandle());
						}catch (Exception e) {
							e.printStackTrace();
						}
						continue;
					}
					//execute OCR
					String result = OCR("image."+ending);
					//send result to manager
					try {
						sendMessage(workerToManagerUrl,msg.getBody()+" "+result, "workerTomanagerQueue", "");
						sqs.deleteMessage(managerToWorkerUrl, msg.getReceiptHandle());
					}catch (Exception e) {
						e.printStackTrace();
					}
					File f = new File("image."+ending);
					System.out.println("deleting file...");
					f.delete();
					System.out.println("file deleted");
				
				}
				try {
					TimeUnit.SECONDS.sleep(10);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
				messages = sqs.receiveMessage(req).getMessages();
			}
		
}
	private static String OCR(String imageName) {
		
		Ocr.setUp(); // one time setup
		Ocr ocr = new Ocr(); // create a new OCR engine
		ocr.startEngine("eng", Ocr.SPEED_FASTEST); // English
		String s = ocr.recognize(new File[] {new File(imageName)},
		Ocr.RECOGNIZE_TYPE_ALL, Ocr.OUTPUT_FORMAT_PLAINTEXT); // PLAINTEXT | XML | PDF | RTF
		ocr.stopEngine();
		return s;
	}
	private static void sendMessage(String queueUrl, String message,String queueName, String messageName) {
		System.out.println("Sending "+ messageName+" to "+queueName);
		try {
	    sqs.sendMessage(new SendMessageRequest(queueUrl,
	            message));	
		}catch (Exception e) {
		}
	}
	
	private static String downloadFile(String stringUrl) {
		URL url;
		try {
			url = new URL(stringUrl);
			String[] splits = stringUrl.split("\\.");	
			System.out.println("string url: " + stringUrl);
			InputStream in = new BufferedInputStream(url.openStream());
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buf = new byte[1024];
			int n = 0;
			//read byte by byte
			while (-1!=(n=in.read(buf)))
			{
			   out.write(buf, 0, n);
			}
			out.close();
			in.close();
			byte[] response = out.toByteArray();
			FileOutputStream fos = new FileOutputStream("image."+splits[splits.length-1]);
			fos.write(response);
			fos.close();
			return splits[splits.length-1];
		} catch (MalformedURLException e) {
			System.err.println("Cannot open url:"+stringUrl);
			e.printStackTrace();
		} catch (IOException e) {
			System.err.println("Cannot download Image:"+stringUrl);
			e.printStackTrace();
		}
		return "file not found";
		
		
	}

	private static void initState() {
		AWSCredentials credentials =  new DefaultAWSCredentialsProviderChain().getCredentials();
		credentials.getAWSAccessKeyId();
		credentials.getAWSSecretKey();
		AWSStaticCredentialsProvider credentialsProvider = new AWSStaticCredentialsProvider(credentials);

		sqs = AmazonSQSClientBuilder.standard()
				.withCredentials(credentialsProvider)
				.withRegion("us-east-1")
				.build();
		System.out.println("Successflly intialized: sqs");

		
	}
}
