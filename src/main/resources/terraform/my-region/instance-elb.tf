resource "aws_elb" "{{key}}" {
  name            = "ligoj-${var.subscription}-{{key}}"
  subnets         = ["${module.vpc.public_subnets}"]
  internal        = false
  security_groups = ["${aws_security_group.default_elb.id}"]

  connection_draining         = true
  connection_draining_timeout = 10
  cross_zone_load_balancing   = true
  idle_timeout                = "${var.idle_timeout}"
  tags                        = "${merge(var.tags, map("Name", "{{name}}"))}"

  listener = [
    {
      instance_port     = "80"
      instance_protocol = "HTTP"
      lb_port           = "80"
      lb_protocol       = "HTTP"
    },
  ]
  health_check = [
    {
      target              = "HTTP:80/"
      interval            = 30
      healthy_threshold   = 2
      unhealthy_threshold = 2
      timeout             = 5
    },
  ]
  access_logs  = []
}
