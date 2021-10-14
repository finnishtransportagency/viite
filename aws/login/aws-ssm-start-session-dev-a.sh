#!/bin/bash
#aws ssm start-session --target i-0e2e146d3e05c139b --document-name AWS-StartPortForwardingSession --parameters portNumber=22,localPortNumber=2222 --profile vaylaapp
aws ssm start-session --target i-0d787b2ff954e1789 --document-name AWS-StartPortForwardingSession --parameters portNumber=22,localPortNumber=2222 --profile vaylaapp

# QA
#ssh -i C:\Users\ari.orre\.ssh\Viite-QATEST.pem -p 2222 ec2-user@localhost -L 8073:ip-100-64-26-20:80
