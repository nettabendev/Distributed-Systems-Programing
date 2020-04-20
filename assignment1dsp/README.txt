Names and i.d.'s:
Avraham Cohen 305581779
Netta Ben Ezri 311497994

How to run the project:
run localApplication as maven project from cmd or ide's such as eclipse, intellij, netbeans etc..
 with arguments <inputFilePath> <numberOfImagesPerWorker>

How the application work:
	The Local Application uploads the file with the list of images URLs to our S3 account.
	It creates 2 queues for communication from and to The Manager Instance.
	Then it sends a message stating of the location of the list on S3.
	Then it checks if a manager is active and if not, starts it.
	
	The Manager opens a SQS queue for communication with the upcoming workers.
	The Manager downloads list of images and create an SQS message for each URL in the list of images.
	The Manager creates Worker instances and send the SQS messages with the URL to the queue in line 14 simultaniously. 
	
	Each Worker gets a message containing an image URL from the SQS ManagerToWorker queue.
	Then it downloads the image from the URL, then applies OCR on image.
	Finally, the worker puts a message in the workerToManager queue indicating the original URL of the image and the decoded text.
	
	
	Manager reads all the Workers' messages from workerToManager and creates a summary file.
	Manager uploads the summary file to S3 and sends a message containing its location to the localApplication.
	
	Local Application reads SQS message, downloads the summary file from S3, and creates html output files.
	
	
Mandatory requirements from the website:
	
	Did you think for more than 2 minutes about security? Do not send your credentials in plain text! 
		We did not send credentials as plain text. We defined them as environment variables and sent pointers to them. 
	
	Did you think about scalability? Will your program work properly when 1 million clients connected at the same time? How about 2 million?
	1 billion? Scalability is very important aspect of the system, be sure it is scalable!
		In our implementation every application of the localApplication will generate new uniqe instances and queues for the run, therefore
		scalability is preserved. 
	
	What about persistence? What if a node dies? What if a node stalls for a while?
	Have you taken care of all possible outcomes in the system? Think of more possible issues that might arise from failures. 
	What did you do to solve it? What about broken communications? Be sure to handle all fail-cases!
		In the closing of instances we addressed the issues that occure due to comminicaion failures, and remote computers failures.
		We made sure all messages has been hadled, and that at the end of the application all instances and queues are terminated and deleted.
		
	Threads in your application, when is it a good idea? When is it bad? Invest time to think about threads in your application!
		We did not use threads in our app, since the tasks are fairly devided by the differant computers.
		
	Do you understand how the system works? Do a full run using pen and paper, draw the different parts and the communication that happens
	between them.
		In the powerPoint commmuni-expln file.
	
	Did you manage the termination process? Be sure all is closed once requested!
		Yes. We made sure that al pending and running processes will be terminated using filters.
		
	Did you take in mind the system limitations that we are using? Be sure to use it to its fullest!
		We followed the assignment's specification as well as we could, and so we manipulated the work of each process.
		
	Are all your workers working hard? Or some are slacking? Why?
		The work load and message reciving is ordered by first free first catch. Each avaliable worker handle a messsage , and only when it is done
		with it it receives a new one. So adventually all processes end in aproximatly the same time.
		This is due to the fact that the worker never stop looking for messages untill the Manager terminates it.
		
	Is your manager doing more work than he's supposed to? Have you made sure each part of your system has properly defined tasks?
	Did you mix their tasks? Don't!
		We did everything by the book.
		
	What about security? Putting passworded zip file in a public bucket is wrong!
		We are the only ones who can get the jars in the bucket.
		
	Lastly, are you sure you understand what distributed means? Is there anything in your system awaiting another? 
		The LocalApplication waits for the Manager who waits for the workers. Each one of the above run on a differant computer, hance 
		distributed.