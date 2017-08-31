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
/* PUBLIC subnet */
resource "aws_subnet" "PUBLIC" {
  vpc_id     = "${aws_vpc.terraform.id}"
  cidr_block = "10.0.1.0/24"
  map_public_ip_on_launch = true
  tags = {
    Project = "gStack"
    Name = "PUBLIC"
  }
}
resource "aws_internet_gateway" "default" {
  vpc_id     = "${aws_vpc.terraform.id}"
}
resource "aws_route_table" "PUBLIC" {
  vpc_id     = "${aws_vpc.terraform.id}"
  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = "${aws_internet_gateway.default.id}"
  }
  tags = {
    Project = "gStack"
    Name = "PUBLIC"
  }
}
resource "aws_route_table_association" "PUBLIC" {
    subnet_id = "${aws_subnet.PUBLIC.id}"
    route_table_id = "${aws_route_table.PUBLIC.id}"
}
/* PRIVATE_NAT subnet */
resource "aws_subnet" "PRIVATE_NAT" {
  vpc_id     = "${aws_vpc.terraform.id}"
  cidr_block = "10.0.2.0/24"
  tags = {
    Project = "gStack"
    Name = "PRIVATE_NAT"
  }
}
resource "aws_eip" "PRIVATE_NAT" {
  vpc      = true
}
resource "aws_nat_gateway" "default" {
  allocation_id = "${aws_eip.PRIVATE_NAT.id}"
  subnet_id     = "${aws_subnet.PUBLIC.id}"
}
resource "aws_route_table" "PRIVATE_NAT" {
  vpc_id     = "${aws_vpc.terraform.id}"
  route {
    cidr_block = "0.0.0.0/0"
        nat_gateway_id = "${aws_nat_gateway.default.id}"
  }
  tags = {
    Project = "gStack"
    Name = "PRIVATE_NAT"
  }
}
resource "aws_route_table_association" "PRIVATE_NAT" {
    subnet_id = "${aws_subnet.PRIVATE_NAT.id}"
    route_table_id = "${aws_route_table.PRIVATE_NAT.id}"
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
resource "aws_instance" "vm-dev" {
  ami           = "${data.aws_ami.ami-LINUX.id}"
  instance_type = "t2.micro"
  key_name    	= "gStack-key"
  vpc_security_group_ids = [ "${aws_security_group.vm-sg.id}" ]
  subnet_id     = "${aws_subnet.PRIVATE_NAT.id}"
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
  owners = ["123456789", "amazon"]
}
