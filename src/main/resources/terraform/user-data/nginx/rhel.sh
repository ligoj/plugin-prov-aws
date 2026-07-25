#!/bin/bash
yum -y update
echo '[nginx]
name=nginx repo
baseurl=http://nginx.org/packages/mainline/rhel/7/$basearch/
gpgcheck=0
enabled=1' > /etc/yum.repos.d/nginx.repo
yum -y install initscripts nginx
service nginx start
