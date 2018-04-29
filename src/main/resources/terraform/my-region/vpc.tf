locals {
  azs             = ["${sort(formatlist("${var.region}%s", var.azs))}"]
  private_subnets = ["10.0.1.0/24", "10.0.2.0/24"]                      #, "10.0.2.0/24", "10.0.3.0/24"]
  public_subnets  = ["10.0.101.0/24", "10.0.102.0/24"]                  #, "10.0.102.0/24", "10.0.103.0/24"]
}

data "aws_availability_zones" "available" {
  state = "available"
}

module "vpc" {
  source             = "terraform-aws-modules/vpc/aws"
  tags               = "${var.tags}"
  cidr               = "${var.cidr}"
  name               = "${var.project_key}"
  azs                = ["${local.azs}"]
  private_subnets    = ["${local.private_subnets}"]
  public_subnets     = ["${local.public_subnets}"]
  enable_nat_gateway = false
  enable_vpn_gateway = false
}

resource "aws_key_pair" "auth" {
  key_name   = "${var.key_name}"
  public_key = "${var.public_key}"
}

module "sg_rule" {
  source         = "git::https://github.com/fabdouglas/terraform-aws-easy-sg_rule.git"
  ingress        = "${var.ingress}"
  security_group = "${aws_security_group.default.id}"

  #security_group = "${module.vpc.default_security_group_id}"
}

module "sg_rule_elb" {
  source         = "git::https://github.com/fabdouglas/terraform-aws-easy-sg_rule.git"
  ingress        = "${var.ingress-elb}"
  security_group = "${aws_security_group.default_elb.id}"
}

resource "aws_security_group" "default" {
  name        = "${var.project_key}"
  description = "Default SG for all EC2"
  vpc_id      = "${module.vpc.vpc_id}"
  tags        = "${var.tags}"
}

resource "aws_security_group" "default_elb" {
  name        = "${var.project_key}-elb"
  description = "Default SG for all ELBs"
  vpc_id      = "${module.vpc.vpc_id}"
  tags        = "${var.tags}"
}
