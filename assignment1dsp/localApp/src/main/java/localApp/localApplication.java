package localApp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;

import com.amazonaws.services.ec2.model.Tag;
import com.amazonaws.services.ec2.model.TagSpecification;
import com.amazonaws.services.ec2.model.TerminateInstancesRequest;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.SdkClientException;
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
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.CannedAccessControlList;
import com.amazonaws.services.s3.model.ListVersionsRequest;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.amazonaws.services.s3.model.S3VersionSummary;
import com.amazonaws.services.s3.model.VersionListing;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import com.amazonaws.services.sqs.model.CreateQueueRequest;
import com.amazonaws.services.sqs.model.DeleteQueueRequest;
import com.amazonaws.services.sqs.model.Message;
import com.amazonaws.services.sqs.model.QueueDoesNotExistException;
import com.amazonaws.services.sqs.model.SendMessageRequest;
import com.amazonaws.services.sqs.model.SetQueueAttributesRequest;
import com.amazonaws.util.Base64;
import com.amazonaws.services.ec2.model.Filter;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.InstanceType;
import com.amazonaws.services.ec2.model.Reservation;
import com.amazonaws.services.ec2.model.RunInstancesRequest;
import com.amazonaws.services.ec2.model.RunInstancesResult;

public class localApplication {
	
	private static AmazonEC2 ec2;
	private static AmazonS3 s3;
	private static AmazonSQS sqs;
	private static String AWS_ACCESS_KEY_ID;
	private static String AWS_SECRET_ACCESS_KEY;
	private static String localAppTOManngerQueueUrl;
	private static String managerToLocalAppQueueUrl;
	private static String keyName;

	public static void main(String[] args) {
		System.out.println(new SimpleDateFormat("dd/MM/yyyy HH:mm:ss").format(new Date()));
		File inputFile = new File( args[0]);
		String[] countLines = readLineByLine(inputFile.getPath());
		//can only open 20 ec2 instances - 1 Manager and 19 Worker
		int minimumNumOfImagePerWorker = (int) Math.ceil(countLines.length/19);
		
		int numOfImagesPerWorker = Integer.valueOf(args[1]);
		if (numOfImagesPerWorker < 1 ) 
			throw new NumberFormatException("Error: Number of images per worker MUST BE POSITIVE");
		
		if(numOfImagesPerWorker<minimumNumOfImagePerWorker) {
			 BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
			 System.out.println("Number of images per worker should be at least: "+minimumNumOfImagePerWorker);
			System.out.println("do you want to continue with this number? [y/n]");
	        String s ="";
	        do {
				try {
					s = br.readLine();
				} catch (IOException e) {
					e.printStackTrace();
				}
				if (s.equals("y")) {
					numOfImagesPerWorker = minimumNumOfImagePerWorker;
					break;
				}
				else {
					if(s.equals("n")) System.exit(1);
					System.out.println("Please enter [y/n]");
				}
	        }while(true);
		}
		System.out.println("numberOfImagesPerWorker: "+numOfImagesPerWorker);
		//potential failure 
		
		
		// starts/enables the connection to ec2, sqs and s3 in our account's credentials
		initState();
		
		//create messages queues for communication from localApplication to manager and vise versa
		localAppTOManngerQueueUrl = createQueue("localAppToManager");
		managerToLocalAppQueueUrl = createQueue("managerToLocalApp");
		
		//create bucket on our s3 account
		String bucket = createBucket();
		
		String location = uploadFileToBucket(inputFile,bucket);
		
		//send location of inputfile on s3
		sendMessage(localAppTOManngerQueueUrl,location+" "+numOfImagesPerWorker,"localAppToManager",
				"inputFile location and numberOfImagesPerWorker");
		
		//create manger instance if not exists
		keyName = getManeger();
		
		//set request of waiting policy
		SetQueueAttributesRequest set_attrs_request = new SetQueueAttributesRequest()
			        .withQueueUrl(managerToLocalAppQueueUrl)
			        .addAttributesEntry("ReceiveMessageWaitTimeSeconds", "20");
		
		//apply waiting policy, thus waiting for summary file
		sqs.setQueueAttributes(set_attrs_request);
		List< Message> messages = new ArrayList<Message>();
		System.out.println("waiting for summary file...");
		boolean done  = false;
		while(!done) {
			try {
			List<Message> l = sqs.receiveMessage(managerToLocalAppQueueUrl).getMessages();

			if(!l.isEmpty()) {
				for(Message m:l) {
					if (!m.getBody().equals("done")) messages.add(m);
					else {
						if(!messages.isEmpty()) done = true;
					}
				}
			}
			}catch (Exception e) {
				System.out.println("can't find queue");
			}
			try {
				TimeUnit.SECONDS.sleep(10);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
		}
		closeManager();
		deleteQueue(localAppTOManngerQueueUrl);
		deleteQueue(managerToLocalAppQueueUrl);
		System.out.println("Successfully deleted queues: local app to manager, manager to local app" );
		
		deleteBucket(bucket);
		
		///////create html
		
		if(messages.size()!=1) System.err.println("Wrong number of messages in managerToLocalAppQueue");
		PrintWriter writer;
		//head of html:
		File outFile = new File("output.html");
		String summaryBucketUrl="";
			try {
				writer = new PrintWriter(outFile, "UTF-8");
				writer.println("\r\n" + 
						"<html>\r\n" + 
						"<title>OCR</title>\r\n" + 
						"<body>\n");
				
			
				summaryBucketUrl = messages.get(0).getBody();
				//receive summary file from manager
				getFileFromS3(messages.get(0).getBody(),"summary.txt");
				String[] content = readLineByLine("summary.txt");
				writer.println("\t\t<p>");
				writer.println("\t\t\t<img src="+content[0].split(" ")[0]+"><br/>");
				writer.println("\t\t\t"+content[0].split(" ")[1]+"</br>");
				for (int i=1;i<content.length;i++) {
					if(content[i].equals("\n")) continue;
					if(content[i].length()>7) {
						if(content[i].substring(0, 4).equals("http")) {
							writer.println("\t\t</p>");
							writer.println("\t\t<p>");
							String[] spl = content[i].split(" ");
							writer.println("\t\t\t<img src="+spl[0]+"><br/>");
							if (spl.length>1) writer.println("\t\t\t"+spl[1]+"</br>");
							continue;
						}
					}
						writer.println("\t\t\t"+content[i]+"</br>");
				}
				writer.println("\t\t</p>");
				writer.println("</body>\r\n" + 
						"<html>");
					
				
				writer.close();
				
			} catch (FileNotFoundException e) {
				System.err.println("No summary file");
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			}
//			try {
//				TimeUnit.SECONDS.sleep(10);
//			} catch (InterruptedException e) {
//				e.printStackTrace();
//			}
			//getting ready to exit the program

			deleteBucket(summaryBucketUrl.split("//")[1].split("\\.")[0]);
			
			File f = new File("summary.txt");
			f.delete();
			
			System.out.println("finished, the html file is C:\\Users\\User\\Desktop\\assingment1\\output.html");
			System.out.println(new SimpleDateFormat("dd/MM/yyyy HH:mm:ss").format(new Date()));

	}
	
	private static String[] readLineByLine(String filePath)
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
	    return ans.split("\n");
	}
	
	private static void deleteBucket(String bucketName) {
		
		System.out.println("deleting bucket: "+bucketName);
		try {
			/*receive bucket's content and delete it*/
			
			ObjectListing objectListing = s3.listObjects(bucketName);
	        while (true) {
	        	
	            Iterator<S3ObjectSummary> objIter = objectListing.getObjectSummaries().iterator();
	            while (objIter.hasNext()) {
	            	 s3.deleteObject(bucketName, objIter.next().getKey());
	            }
	 
	             // If the bucket contains many objects, the listObjects() call
	             // might not return all of the objects in the first listing. Check to
	             // see whether the listing was truncated. If so, retrieve the next page of objects 
	             // and delete them.
	             if (objectListing.isTruncated()) {
	                 objectListing = s3.listNextBatchOfObjects(objectListing);
	             } else {
	                 break;
	             }
	         }
	 
	         // Delete all object versions (required for versioned buckets).
	         VersionListing versionList = s3.listVersions(new ListVersionsRequest().withBucketName(bucketName));
	         while (true) {
	             Iterator<S3VersionSummary> versionIter = versionList.getVersionSummaries().iterator();
	             while (versionIter.hasNext()) {
	                 S3VersionSummary vs = versionIter.next();
	                 s3.deleteVersion(bucketName, vs.getKey(), vs.getVersionId());
	             }
	 
	             if (versionList.isTruncated()) {
	                 versionList = s3.listNextBatchOfVersions(versionList);
	             } else {
	                 break;
	             }
	         }
	 
	         // After all objects and object versions are deleted, delete the bucket.
	         s3.deleteBucket(bucketName);
	 		System.out.println("Bucket " +bucketName+ " deleted");
	
	     }
     catch(AmazonServiceException e) {
         // The call was transmitted successfully, but Amazon S3 couldn't process 
         // it, so it returned an error response.
         e.printStackTrace();
     }
     catch(SdkClientException e) {
         // Amazon S3 couldn't be contacted for a response, or the client couldn't
         // parse the response from Amazon S3.
         e.printStackTrace();
     }		
	}

	private static void deleteQueue(String queueUrl) {
		System.out.println("Deleting queue:" +queueUrl);
		try {
		sqs.deleteQueue(new DeleteQueueRequest(queueUrl));		
		}
		catch (AmazonServiceException e) {
			e.printStackTrace();
		}
	}
	
	private static void closeManager() {
		System.out.println("Closing manager...");
		
		DescribeInstancesRequest request = new DescribeInstancesRequest();
		//filter will find our manager (with a unique name) instance to be closed 
		Filter filter1 = new Filter("instance-state-name").withValues("running","pending");
		Filter filter2 = new Filter("key-name").withValues(keyName);

		DescribeInstancesResult result = ec2.describeInstances(
				request.withFilters(filter1,filter2));

		List<Reservation> reservations = result.getReservations();
		
		for (Reservation reservation : reservations) {
		    List<Instance> instances = reservation.getInstances();
		    //in fact the list contains one instance and it is our manager
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
		if(!ec2.describeInstances(request.withFilters(filter1,filter2)).getReservations().isEmpty()) closeManager();

		DeleteKeyPairRequest req = new DeleteKeyPairRequest().withKeyName(keyName);
		ec2.deleteKeyPair(req);
		
		System.out.println("Manager successfully closed");
	}

	private static File getFileFromS3(String fileUrl, String fileName) {
		/* fileName- the name of the file that will be saved in our computer
		 * fileUrl- the url of the location of the file on s3
		 */
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

private static String createQueue(String queueName) {
	try {//make sure that there are no duplications of the queues
		sqs.getQueueUrl(queueName).getQueueUrl();
	}
	catch (QueueDoesNotExistException e) {//create the queue
		System.out.println("Creating a new SQS queue called "+ queueName);
	    final CreateQueueRequest createQueueRequest =
	            new CreateQueueRequest(queueName);
	    return  sqs.createQueue(createQueueRequest)
	            .getQueueUrl();
	}
	return sqs.getQueueUrl(queueName).toString();
}

private static void sendMessage(String queueUrl, String message,String queueName, String messageName) {
	System.out.println("Sending "+ messageName+" to "+queueName);
    sqs.sendMessage(new SendMessageRequest(queueUrl,
            message));		
	}
private static String uploadFileToBucket(File inputFile, String bucket) {
                /* Uploads inputFile to bucket */
                
                //converts the name of the file+path to a name of file pattern
		String keyName = inputFile.getName().replace('\\', '_').replace('/','_').replace(':', '_');
		System.out.println("Uploding "+ inputFile.getName() +" with key value:  "+ keyName );
		s3.putObject(new PutObjectRequest(
                bucket, keyName, inputFile).withCannedAcl(CannedAccessControlList.PublicRead));
		return String.valueOf(s3.getUrl(
                 bucket, //The S3 Bucket To Upload To
                 inputFile.getName())); //The key for the uploaded object
		
}

	private static String createBucket() {
        /*to use s3 space */
        String bucketName = "ass1-localapp"+ UUID.randomUUID().getMostSignificantBits();
        
        System.out.println("Creating "+bucketName);
        s3.createBucket(bucketName);
        return bucketName;
        
	}
       
	private static String getECSuserData() {
		/* adding credentials to remote computer
		 * coping manager jar from s3
		 * removing java 7 if exists and installing java 8 - this is all to make sure manager jar will be
		 * executed on java 8 version
		 * running manager app
		 */
		return "#!/bin/bash\n"
				+ "aws configure set aws_access_key_id "+AWS_ACCESS_KEY_ID+"\n"
				+ "aws configure set aws_secret_access_key "+AWS_SECRET_ACCESS_KEY+"\n"
				+ "aws s3 cp s3://acs-jars/Manager.jar /home/ec2-user/Manager.jar"+"\n"
				+ "yes | sudo yum remove java-1.7.0-openjdk\n"
				+ "yes | sudo yum install java-1.8.0\n"
				+ "sudo java -jar /home/ec2-user/Manager.jar "
				+ localAppTOManngerQueueUrl +" "+ managerToLocalAppQueueUrl + " " + keyName + "\n";
}


	private static String getManeger() {
		/*look for a type Manager instance and if doesn't exist create one*/
		
		DescribeInstancesRequest request = new DescribeInstancesRequest();
        request.withFilters(new Filter().withName("tag:Type").withValues("Manager"),
        		new Filter().withName("instance-state-name").withValues("pending", "running"));
        
        //receive list of reservations. if empty - then no manager exists
        List<Reservation> reservations = ec2.describeInstances(request).getReservations();
       
        if(reservations.isEmpty()) {
        	keyName = "managerKey"+UUID.randomUUID().getMostSignificantBits();
        	CreateKeyPairRequest createKeyPairRequest = new CreateKeyPairRequest();

        	createKeyPairRequest.withKeyName(keyName);
        	
        	ec2.createKeyPair(createKeyPairRequest);
        	//create Manager. using special keyName
        	TagSpecification tags = new TagSpecification().withResourceType("instance")
    				.withTags(new Tag("Type", "Manager"));    
        	RunInstancesRequest InstanceRequest =
        			new RunInstancesRequest("ami-467ca739", 1, 1)
        			.withInstanceType(InstanceType.T2Micro.toString())
        			.withKeyName(keyName)
        			.withSecurityGroups("mySecGroup")
        			.withUserData(Base64.encodeAsString(getECSuserData().getBytes()))
        			.withTagSpecifications(tags);
        	
	        RunInstancesResult run_response = ec2.runInstances(InstanceRequest);
	
	        String instance_id = run_response.getReservation().getReservationId();
	        
	            System.out.printf(
	                "Successfully started EC2 instance %s based on AMI %s \n",
	                instance_id, "ami-76f0061f");
	     }
        else System.out.println("conecting to existing EC2 instance");
        
        while(true) {
        	if (!ec2.describeInstances(request).getReservations().isEmpty()) break;
        }
        return ec2.describeInstances(request).getReservations().get(0).getInstances().get(0).getKeyName(); 

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
