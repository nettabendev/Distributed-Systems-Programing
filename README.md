# Distributed-Systems-Programing-Assignment1
Assignment1 in the course Distributed System Programing 1 which I took in Ben Gurion University in spring 2018.
The assignment goal is to create a simple program which use aws in order to aplly an ocr algorithm on a list of photoes which are been stored in s3.
The program will have a  locall app that will run things on amazon:
In order to use the resourses that amazon provides we created a manager-worker environment that does that. There is one manager and several workers which communicate with the mannager.
The manager side hold a list of adresses for pictures stored in s3.
Each worker takes some load of the work by communicating with the mannageer side. further explanation of how this code works you can find in the other readme file. 

CREDITS:
This Assignment is the product of the combined work of my partner Abraham Cohen and me.
