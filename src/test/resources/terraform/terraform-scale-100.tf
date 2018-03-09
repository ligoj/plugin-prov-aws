variable "AWS_ACCESS_KEY_ID" {}
variable "AWS_SECRET_ACCESS_KEY" {}
provider "aws" {
  region = "eu-west-1"
  access_key = "${var.AWS_ACCESS_KEY_ID}"
  secret_key = "${var.AWS_SECRET_ACCESS_KEY}"
}
variable publickey {
  description = "SSH Public key used to access nginx EC2 Server"
}
/* network */
resource "aws_vpc" "terraform" {
  cidr_block = "10.0.0.0/16"
}
/* PRIVATE subnet */
resource "aws_subnet" "PRIVATE" {
  vpc_id     = "${aws_vpc.terraform.id}"
  cidr_block = "10.0.3.0/24"
  tags = {
    Project = "gStack"
    Name = "PRIVATE"
  }
}
/* security group */
resource "aws_security_group" "vm-sg" {
  name        = "gStack-sg"
  description = "Allow ssh inbound traffic, all inbound traffic in security group and all outbund traffic"
  vpc_id     = "${aws_vpc.terraform.id}"
  ingress {
    from_port   = 22
    to_port     = 22
    protocol    = "TCP"
    cidr_blocks = ["0.0.0.0/0"]
  }
  ingress {
    from_port   = 0
    to_port     = 0
    protocol    = "TCP"
    self        = true
  }
  egress {
    from_port = 0
    to_port = 0
    protocol = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
  tags = {
    Project = "gStack"
    Name = "gStack"
  }
}
/* key pair*/
resource "aws_key_pair" "vm-keypair" {
  key_name   = "gStack-key"
  public_key = "${var.publickey}"
}
/* instance */
resource "aws_launch_configuration" "vm-dev" {
  image_id           = "${data.aws_ami.ami-LINUX.id}"
  name    		= "dev"
  instance_type = "t2.micro"
  key_name    	= "gStack-key"
  security_groups = [ "${aws_security_group.vm-sg.id}" ]
}
/* autoscaling */
resource "aws_autoscaling_group" "devautoscaling-group" {
  min_size                  = 100
  max_size                  = 100
  launch_configuration      = "${aws_launch_configuration.vm-dev.name}"
  vpc_zone_identifier     = ["${aws_subnet.PRIVATE.id}"]
  tags = [{
    key="Project"
    value="gStack"
    propagate_at_launch = true
  }, {
    key="Name"
    value="gStack-dev"
    propagate_at_launch = true
  }]
}
/* search ami id */
data "aws_ami" "ami-LINUX" {
  most_recent = true
  filter {
    name   = "name"
    values = ["amzn-ami-hvm-*-x86_64-gp2"]
  }
  filter {
    name   = "virtualization-type"
    values = ["hvm"]
  }
  owners = ["123456789", "amazon"]
}
