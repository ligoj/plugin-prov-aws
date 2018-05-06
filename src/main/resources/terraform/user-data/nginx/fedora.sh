#!/bin/bash
dnf install nginx
systemctl enable nginx.service
systemctl start nginx.service
