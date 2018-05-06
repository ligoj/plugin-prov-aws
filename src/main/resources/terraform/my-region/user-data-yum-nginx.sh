#!/bin/bash
yum -y update
yum -y install initscripts nginx
service nginx start
