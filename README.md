# Distributed-Systems-Programing

The program's goal is to create a simple program which use aws in order to aplly an ocr algorithm on a list of photoes which are been stored in s3.
The program will have a  locall app that will run things on amazon:
In order to use the resourses that amazon provides we created a manager-worker environment that does that. There is one manager and several workers which communicate with the mannager.
The manager side hold a list of adresses for pictures stored in s3.
Each worker takes some load of the work by communicating with the mannageer side. further explanation of how this code works you can find in the other readme file. 

CREDITS:
This program is the product of the combined work of me and my partner Abraham Cohen.
