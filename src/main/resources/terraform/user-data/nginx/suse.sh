#!/bin/bash
zypper install nginx
systemctl enable nginx
systemctl start nginx
