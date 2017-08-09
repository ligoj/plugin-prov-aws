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
/* security group */
resource "aws_security_group" "vm-sg" {
  name        = "gStack-sg"
  description = "Allow ssh inbound traffic and all outbund traffic"
  ingress {
    from_port   = 22
    to_port     = 22
    protocol    = "TCP"
    cidr_blocks = ["0.0.0.0/0"]
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
resource "aws_spot_instance_request" "vm-dev" {
  spot_price    = "0.03"
  ami           = "${data.aws_ami.ami-LINUX.id}"
  instance_type = "t2.micro"
  key_name    	= "gStack-key"
  vpc_security_group_ids = [ "${aws_security_group.vm-sg.id}" ]
  tags = {
    Project = "gStack"
    Name = "gStack-dev"
  }
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
  owners = ["12346789", "amazon"]
}
