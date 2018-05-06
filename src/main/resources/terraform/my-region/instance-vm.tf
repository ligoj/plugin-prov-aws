resource "aws_instance" "{{key}}" {
  ami                    = "${module.ami_{{os}}.ami_id}"
  subnet_id              = "${element(module.vpc.public_subnets, 0)}"
  instance_type          = "{{type}}"
  vpc_security_group_ids = ["${aws_security_group.default.id}"]
  key_name               = "${aws_key_pair.auth.id}"
  tags                   = "${merge(var.tags, map("Name", "{{name}}"))}"

  connection {
    type = "ssh"
    user = "ec2-user"
  }
{{root-device}}{{user-data}}
}
{{ebs-devices}}