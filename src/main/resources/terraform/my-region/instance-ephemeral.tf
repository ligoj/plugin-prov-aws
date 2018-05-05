resource "aws_spot_instance_request" "{{key}}" {
  ami                    = "${module.ami_{{os}}.ami_id}"
  subnet_id              = "${element(module.vpc.public_subnets, 0)}"
  instance_type          = "{{type}}"
  vpc_security_group_ids = ["${aws_security_group.default.id}"]
  key_name               = "${aws_key_pair.auth.id}"
  tags                   = "${merge(var.tags, map("Name", "{{name}}"))}"
  spot_price             = "{{spot-price}}"

  connection {
    type = "ssh"
    user = "ec2-user"
  }
{{root-device}}
  provisioner "remote-exec" {
    inline = [
      "sudo yum -y update",
      "sudo yum -y install initscripts nginx",
      "sudo service nginx start",
    ]
  }
}
{{ebs-devices}}