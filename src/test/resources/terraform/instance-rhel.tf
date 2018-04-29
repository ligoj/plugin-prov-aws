resource "aws_instance" "instancea" {
  ami                    = "${module.ami_rhel.ami_id}"
  subnet_id              = "${element(module.vpc.public_subnets, 0)}"
  instance_type          = "t2.micro"
  vpc_security_group_ids = ["${aws_security_group.default.id}"]
  key_name               = "${aws_key_pair.auth.id}"
  tags                   = "${merge(var.tags, map("Name", "InstanceA"))}"

  connection {
    type = "ssh"
    user = "ec2-user"
  }

  provisioner "remote-exec" {
    inline = [
      "sudo yum -y update",
      "sudo yum -y install initscripts nginx",
      "sudo service nginx start",
    ]
  }
}

resource "aws_volume_attachment" "instancea-storage-0" {
  device_name = "/dev/sda1"
  volume_id   = "${aws_ebs_volume.instancea-storage-0.id}"
  instance_id = "${module.instancea.instance}"
}

resource "aws_ebs_volume" "instancea-storage-0" {
  availability_zone = "${element(local.azs, 0)}"
  size              = 10
  tags              = "${merge(var.tags, "Name", "instancea-instancea-storage-0")}"
}

resource "aws_volume_attachment" "instancea-storage-1" {
  device_name = "/dev/sdf"
  volume_id   = "${aws_ebs_volume.instancea-storage-1.id}"
  instance_id = "${module.instancea.instance}"
}

resource "aws_ebs_volume" "instancea-storage-1" {
  availability_zone = "${element(local.azs, 0)}"
  size              = 8
  tags              = "${merge(var.tags, "Name", "instancea-instancea-storage-1")}"
}
