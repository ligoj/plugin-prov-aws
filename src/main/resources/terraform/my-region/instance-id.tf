module "{{key}}" {
  source          = "git::https://github.com/fabdouglas/terraform-aws-abstract-instance"
  name            = "${var.project_key}-{{key}}"
  short_name      = "ligoj-${var.subscription}-{{key}}"
  ami             = "${module.ami_{{os}}.ami_id}"
  instance_type   = "{{type}}"
  security_groups = ["${aws_security_group.default.id}"]
  key_name        = "${aws_key_pair.auth.id}"
  tags            = "${var.tags}"
  subnets         = ["${module.vpc.public_subnets}"]
  vpc_id          = "${module.vpc.vpc_id}"
  spot_price      = "{{spot-price}}"
}
{{ebs-devices}}