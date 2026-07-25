#!/bin/bash
yum -y update
yum -y install epel-release
yum -y install initscripts nginx
service nginx start
