resource "aws_instance" "instancea" {
  ami                    = "${module.ami_amazon.ami_id}"
  subnet_id              = "${element(module.vpc.public_subnets, 0)}"
  instance_type          = "t2.micro"
  vpc_security_group_ids = ["${aws_security_group.default.id}"]
  key_name               = "${aws_key_pair.auth.id}"
  tags                   = "${merge(var.tags, map("Name", "InstanceA"))}"

  connection {
    type = "ssh"
    user = "ec2-user"
  }


root_block_device {
  volume_type   = "gp2"
  volume_size   = 10
}  user_data = <<-EOF
#!/bin/bash
yum -y update
yum -y install initscripts nginx
service nginx start

  EOF
}
