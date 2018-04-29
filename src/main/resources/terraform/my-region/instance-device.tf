
resource "aws_volume_attachment" "{{key}}" {
  device_name = "{{device}}"
  volume_id   = "${aws_ebs_volume.{{key}}.id}"
  instance_id = "${module.{{instance}}.instance}"
}

resource "aws_ebs_volume" "{{key}}" {
  availability_zone = "${element(local.azs, 0)}"
  size              = {{size}}
  tags              = "${merge(var.tags, "Name", "{{instance}}-{{key}}")}"
}
