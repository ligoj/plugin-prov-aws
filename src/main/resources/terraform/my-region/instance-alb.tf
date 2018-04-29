resource "aws_lb" "{{key}}" {
  load_balancer_type         = "application"
  name                       = "ligoj-${var.subscription}-{{key}}"
  internal                   = false
  security_groups            = ["${aws_security_group.default_elb.id}"]
  subnets                    = ["${module.vpc.public_subnets}"]
  idle_timeout               = "${var.idle_timeout}"
  enable_deletion_protection = "${var.enable_deletion_protection}"
  enable_http2               = "${var.enable_http2}"
  ip_address_type            = "${var.ip_address_type}"
  tags                       = "${merge(var.tags, map("Name", "{{name}}"))}"

  access_logs {
    enabled = false
    bucket  = ""
  }

  timeouts {
    create = "${var.load_balancer_create_timeout}"
    delete = "${var.load_balancer_delete_timeout}"
    update = "${var.load_balancer_update_timeout}"
  }
}

resource "aws_lb_target_group" "{{key}}" {
  name                 = "ligoj-${var.subscription}-{{key}}"
  vpc_id               = "${module.vpc.vpc_id}"
  port                 = "80"
  protocol             = "HTTP"
  deregistration_delay = "${var.it ? 15 : 300}"
  target_type          = "instance"

  health_check {
    interval            = "${var.it ? 5 : 10}"
    path                = "/"
    port                = "traffic-port"
    healthy_threshold   = "${var.it ? 2 : 3}"
    unhealthy_threshold = "${var.it ? 2 : 3}"
    timeout             = "${var.it ? 3 : 5}"
    protocol            = "HTTP"
    matcher             = "200-299"
  }

  stickiness {
    type            = "lb_cookie"
    cookie_duration = "${var.it ? 15 : 86400}"
    enabled         = true
  }

  tags       = "${merge(var.tags, map("Name", "{{name}}"))}"
  depends_on = ["aws_lb.{{key}}"]

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_lb_listener" "{{key}}" {
  load_balancer_arn = "${aws_lb.{{key}}.arn}"
  port              = "80"
  protocol          = "HTTP"

  default_action {
    target_group_arn = "${aws_lb_target_group.{{key}}.id}"
    type             = "forward"
  }
}
