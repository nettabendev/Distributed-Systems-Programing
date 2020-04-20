package manager;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.services.apigateway.model.NotFoundException;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.ec2.model.CreateKeyPairRequest;
import com.amazonaws.services.ec2.model.DeleteKeyPairRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesResult;
import com.amazonaws.services.ec2.model.Filter;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.InstanceType;
import com.amazonaws.services.ec2.model.Reservation;
import com.amazonaws.services.ec2.model.RunInstancesRequest;
import com.amazonaws.services.ec2.model.RunInstancesResult;
import com.amazonaws.services.ec2.model.Tag;
import com.amazonaws.services.ec2.model.TagSpecification;
import com.amazonaws.services.ec2.model.TerminateInstancesRequest;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.CannedAccessControlList;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import com.amazonaws.services.sqs.model.DeleteQueueRequest;
import com.amazonaws.services.sqs.model.Message;
import com.amazonaws.services.sqs.model.QueueDoesNotExistException;
import com.amazonaws.services.sqs.model.ReceiveMessageRequest;
import com.amazonaws.services.sqs.model.SendMessageRequest;
import com.amazonaws.util.Base64;

public class Manager {
	
	private static AmazonEC2 ec2;
	private static AmazonS3 s3;
	private static AmazonSQS sqs;
	private static String AWS_ACCESS_KEY_ID;
	private static String AWS_SECRET_ACCESS_KEY;
	private static String managerToWorkerUrl;
	private static String workerToManagerUrl;
	private static String keyName;

	public static void main(String args[]) {
		
		//start connection
		initState();
		
		while(true) {
			
			String localAppToManagerUrl = args[0];
			List<Message> messages;
			while(true) {//wait for the message to arrive
				messages = sqs.receiveMessage(localAppToManagerUrl).getMessages();
				if(!messages.isEmpty()) {
					sqs.deleteMessage(localAppToManagerUrl,messages.get(0).getReceiptHandle());
					break;
				}
			}
			
			if(messages.size()>1 || messages.isEmpty()) {//avoid potential failures
				System.err.println("wrong number of messages in localAppToManagerQueue ");
				System.exit(1);
			}
			
			if(messages.size()>1 || messages.isEmpty()) {//avoid potential failures
				System.err.println("wrong number of messages in localAppToManagerQueue ");
				System.exit(1);
			}
			
			String[] msgs = messages.get(0).getBody().split(" ");		
			int numberOfImagesPerWorker=Integer.valueOf(msgs[1]);
			System.out.println("num of image per worker:" + numberOfImagesPerWorker);
			String fileUrl = msgs[0];
					
			/*download image file from s3 
			 * and then putting the url's addresses in a list  
			 */
			File file = getFileFromS3(fileUrl,"input.txt");
			String lines[] = readLineByLine(file.getName()).split("\n");
			
			//creating unique queues
			managerToWorkerUrl = createQueue("managerToWorker" + UUID.randomUUID().getMostSignificantBits());
			workerToManagerUrl = createQueue("workerToManger" + UUID.randomUUID().getMostSignificantBits());
//			List<String> inputMessages = new ArrayList<String>();
			
			
			keyName = "worker_"+args[2];
			try {
			  	CreateKeyPairRequest createKeyPairRequest = new CreateKeyPairRequest();
	        	createKeyPairRequest.withKeyName(keyName);
	        	ec2.createKeyPair(createKeyPairRequest);
			}catch (Exception e) {
				e.printStackTrace();
			}
        	createWorker();
        	for(int i=1;i<lines.length/numberOfImagesPerWorker && i<19 ;i++) createWorker();
			int countMessages = 0;
			for (String line : lines) {	
				//create workers in the manner required in the assignment
				if(!line.equals("\n")) {
					try {
					sendMessage(managerToWorkerUrl,line);
					}catch (Exception e) {
						e.printStackTrace();					}
//					inputMessages.add(line);
//					if((countMessages)%numberOfImagesPerWorker == 0) {
//						createWorker();
//						System.out.println("count messages: "+countMessages);
////						try {
////							TimeUnit.SECONDS.sleep(countMessages/numberOfImagesPerWorker);
////							} catch (InterruptedException e) {
////								e.printStackTrace();
////							}
//					}
					countMessages++;
			
				}
			}
//			createWorker();
//			for(int i=1;i<countMessages/numberOfImagesPerWorker;i++) createWorker();

			ReceiveMessageRequest req = new ReceiveMessageRequest(workerToManagerUrl).withMaxNumberOfMessages(10);
			List<Message> outputMessages = new ArrayList<Message>();
			//dealing with received messages 
			List<Message> tmpList = new ArrayList<Message>();;
			while(outputMessages.size()<countMessages) {
				try {
				tmpList = sqs.receiveMessage(req).getMessages();
				}catch (Exception e) {
					e.printStackTrace();
				}
				for(Message msg:tmpList) {
					try {
					sqs.deleteMessage(workerToManagerUrl,msg.getReceiptHandle());
					}catch (Exception e) {
						e.printStackTrace();
					}
//					String gotMsg = msg.getBody();
//					if(gotMsg.contains(" ")) gotMsg = gotMsg.split(" ")[0];
					
//					if(inputMessages.contains(gotMsg)) { // Handle wirker's failure
//						inputMessages.remove(gotMsg);
						outputMessages.add(msg);
//					}
				}
			try {
				TimeUnit.SECONDS.sleep(10);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
			
		
			deleteQueue(managerToWorkerUrl);
			System.out.println("Manager to workers queue succssfully deleted");
			
					
			if(outputMessages.size()!=countMessages) {
				System.out.println("output messages number:"+outputMessages.size()+","
						+ " count massage:" +countMessages);
			}
			
			//create summary file
			PrintWriter writer;
			System.out.println("Working on summary file");
			File summaryFile = new File("summary.txt");
			try {
				writer = new PrintWriter(summaryFile, "UTF-8");	
				for(Message msg : outputMessages) {
					writer.println(msg.getBody());
//					writer.println("\t\t<p>");
//					String[] s = msg.getBody().split(" ")[0];
					
//					writer.println("\t\t\t<img src="+s+"><br/>");
//					writer.println("\t\t\t"+msg.getBody().substring(s.length()));
//					writer.println("\t\t</p>");

				}
				writer.close();
				
				String bucket = createBucket();
				
				String location = uploadFileToBucket(summaryFile,bucket);
				
				String managerToLocalAppUrl = args[1];
				sendMessage(managerToLocalAppUrl,location);
				/*get ready to exit
				 */ 
				closeWorkers();

				DeleteKeyPairRequest request = new DeleteKeyPairRequest().withKeyName(keyName);
				ec2.deleteKeyPair(request);
				
				deleteQueue(workerToManagerUrl);
				System.out.println("Workers to manager queue succssfully deleted");

				//now all instances of workers and communication queues are closed
				sendMessage(managerToLocalAppUrl, "done");
			} catch (FileNotFoundException e) {
				System.err.println("There is no summary file");
				e.printStackTrace();
			} catch (UnsupportedEncodingException e) {
				System.err.println("There is a problem with the encoding (UTF-8)");
				e.printStackTrace();
			}
			

		}
	}
private static void deleteQueue(String queueUrl) {
	System.out.println("Deleting queue:" +queueUrl);
	try {
	sqs.deleteQueue(new DeleteQueueRequest(queueUrl));
	}catch (Exception e) {
		e.printStackTrace();
	}
	}


	private static void closeWorkers() {
		System.out.println("Closing workers..");

		DescribeInstancesRequest request = new DescribeInstancesRequest();
		//finding workers that were opened by this manager only, for scalability
		Filter filter1 = new Filter("instance-state-name").withValues("running","pending");
		Filter filter2 = new Filter("key-name").withValues(keyName);

		DescribeInstancesResult result = ec2.describeInstances(
				request.withFilters(filter1,filter2));

		List<Reservation> reservations = result.getReservations();
		
		for (Reservation reservation : reservations) {
		    List<Instance> instances = reservation.getInstances();

		    for (Instance instance : instances) {
		    	
		    	String instanceId = instance.getInstanceId();
		        try {
				    ec2.terminateInstances(new TerminateInstancesRequest(Arrays.asList(instanceId)));
				  }
				 catch (  AmazonServiceException e) {
				    if (e.getErrorCode().equals("InvalidInstanceID.NotFound")) {
				      throw new NotFoundException("AWS instance " + instanceId + " not found");
				    }
				    throw e;
			     }
			}

		}

		//check if all workers closed
		if(!ec2.describeInstances(request.withFilters(filter1,filter2)).getReservations().isEmpty()) closeWorkers();
		}
		

	private static void createWorker() {
		
		try {
			DescribeInstancesRequest request = new DescribeInstancesRequest();
	        request.withFilters(new Filter().withName("tag:Type").withValues("Worker"),
	        		new Filter().withName("instance-state-name").withValues("pending", "running"));
			TagSpecification tags = new TagSpecification().withResourceType("instance")
					.withTags(new Tag("Type", "Worker"));
	        RunInstancesRequest run_request = new RunInstancesRequest("ami-467ca739",1,1)
	    		.withKeyName(keyName)
	            .withInstanceType(InstanceType.T2Micro.toString())
	            .withTagSpecifications(tags)
	            .withUserData(Base64.encodeAsString(getECSuserData().getBytes()))
				.withSecurityGroups("mySecGroup");
	        RunInstancesResult run_response = ec2.runInstances(run_request);
	
	        String instance_id = run_response.getReservation().getReservationId();
	        System.out.printf(
	                "Successfully started EC2 instance %s based on AMI %s \n",
	                instance_id, "ami-76f0061f");
		}catch (Exception e) {
		}
        
    
         
		
	}

	private static String getECSuserData() {
			return "#!/bin/bash\n"
					+ "aws configure set aws_access_key_id "+AWS_ACCESS_KEY_ID+"\n"
					+ "aws configure set aws_secret_access_key "+AWS_SECRET_ACCESS_KEY+"\n"
					+ "aws s3 cp s3://acs-jars/Worker.jar /home/ec2-user/Worker.jar"+"\n"
					+ "yes | sudo yum remove java-1.7.0-openjdk\n"
					+ "yes | sudo yum install java-1.8.0\n"
					+ "sudo java -jar /home/ec2-user/Worker.jar " 
					+ managerToWorkerUrl + " "+ workerToManagerUrl + "\n";
	}
	

	private static File getFileFromS3(String fileUrl, String fileName) {
		URL url;
		File file = new File(fileName);
		try {
			url = new URL(fileUrl);
			ReadableByteChannel channel = Channels.newChannel(url.openStream());
			
			FileOutputStream fos = new FileOutputStream(file);
			fos.getChannel().transferFrom(channel, 0, Long.MAX_VALUE);
			fos.flush();
			fos.close();

		} catch (MalformedURLException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
		return file;
		
	}

	
	private static String readLineByLine(String filePath)
	{
		String ans="";
	    
	    try (Stream<String> stream = Files.lines( Paths.get(filePath), StandardCharsets.UTF_8))
	    {
	    	StringBuilder contentBuilder = new StringBuilder();
	        stream.forEach(s -> contentBuilder.append(s).append("\n"));
	        ans += contentBuilder.toString();
	    }
	    catch (IOException e)
	    {
	        e.printStackTrace();
	    }
	    return ans;
	}
	
	private static String createQueue(String queueName) {
		//check if queue exist
		try {
			sqs.getQueueUrl(queueName).getQueueUrl();
		}
		catch (QueueDoesNotExistException e) {
			System.out.println("Creating a new SQS queue called "+ queueName);
		    return  sqs.createQueue(queueName)
		            .getQueueUrl();
		}
		return sqs.getQueueUrl(queueName).toString();
		}
	
	private static void sendMessage(String queueUrl, String message) {
	    sqs.sendMessage(new SendMessageRequest(queueUrl,
	            message));		
		}
	
	
	private static String createBucket() {
        
        String bucketName = "ass1-manager"+ UUID.randomUUID().getMostSignificantBits();
        
        System.out.println("Creating "+bucketName);
        s3.createBucket(bucketName);
        return bucketName;
        
	}
	
	private static String uploadFileToBucket(File inputFile, String bucket) {

		String keyName = inputFile.getName().replace('\\', '_').replace('/','_').replace(':', '_');
		System.out.println("Uploding "+ inputFile.getName() +" with key value:  "+ keyName );
		s3.putObject(new PutObjectRequest(
                bucket, keyName, inputFile).withCannedAcl(CannedAccessControlList.PublicRead));
		return String.valueOf(s3.getUrl(
                 bucket, //The S3 Bucket To Upload To
                 inputFile.getName())); //The key for the uploaded object
		  
		
}
	
	private static void initState() {
		
		/* This function starts/enables the connection to ec2, sqs and s3 in our account's credentials
         */
		
		AWSCredentials credentials =  new DefaultAWSCredentialsProviderChain().getCredentials();
		AWS_ACCESS_KEY_ID =credentials.getAWSAccessKeyId();
		AWS_SECRET_ACCESS_KEY =  credentials.getAWSSecretKey();
		AWSStaticCredentialsProvider credentialsProvider = new AWSStaticCredentialsProvider(credentials);

		ec2 = AmazonEC2ClientBuilder.standard()
				.withCredentials(credentialsProvider)
				.withRegion("us-east-1")
				.build();
		sqs = AmazonSQSClientBuilder.standard()
				.withCredentials(credentialsProvider)
				.withRegion("us-east-1")
				.build();
		s3 = AmazonS3ClientBuilder.standard()
				.withCredentials(credentialsProvider)
				.withRegion("us-east-1")
				.build();
		
		System.out.println("Successflly intialized: ec2, sqs, s3");
		
	}


}
