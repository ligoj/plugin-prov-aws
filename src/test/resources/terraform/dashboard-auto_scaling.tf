locals {
  period         = "${var.it ? 60 * 60 : (60 * 60 * 24)}"
  metrics_period = "${var.it ? 60 : (60 * 10)}"
}

data "template_file" "md" {
  template = "${file("${path.module}/dashboard-widgets.tpl.md")}"

  vars {
    project        = "${var.project}"
    project_key    = "${var.project_key}"
    project_name   = "${var.project_name}"
    subscription   = "${var.subscription}"
    ligoj_url      = "${var.ligoj_url}"
    period         = "${local.period}"
    metrics_period = "${local.metrics_period}"
    region         = "${var.region}"
    vpc0           = "${module.vpc.vpc_id}"

asg0     = "${aws_autoscaling_group.instancea.name}"
asg1     = "${aws_autoscaling_group.instanceb.name}"
asg0_name = "InstanceA"
asg1_name = "InstanceB"
alb0     = "${aws_lb.instancea.arn_suffix}"
alb1     = "${aws_lb.instanceb.arn_suffix}"
alb0_tg  = "${aws_lb_target_group.instancea.arn_suffix}"
alb1_tg  = "${aws_lb_target_group.instanceb.arn_suffix}"
alb0_name = "InstanceA"
alb1_name = "InstanceB"
alb0_dns = "${aws_lb.instancea.dns_name}"
alb1_dns = "${aws_lb.instanceb.dns_name}"
  }
}

data "template_file" "widgets" {
  template = "${file("${path.module}/dashboard-widgets.tpl.json")}"

  vars {
    project        = "${var.project}"
    project_key    = "${var.project_key}"
    project_name   = "${var.project_name}"
    subscription   = "${var.subscription}"
    ligoj_url      = "${var.ligoj_url}"
    period         = "${local.period}"
    metrics_period = "${local.metrics_period}"
    region         = "${var.region}"
    md             = "${replace(data.template_file.md.rendered, "\n", "\\n")}"
    vpc0           = "${module.vpc.vpc_id}"

asg0     = "${aws_autoscaling_group.instancea.name}"
asg1     = "${aws_autoscaling_group.instanceb.name}"
asg0_name = "InstanceA"
asg1_name = "InstanceB"
alb0     = "${aws_lb.instancea.arn_suffix}"
alb1     = "${aws_lb.instanceb.arn_suffix}"
alb0_tg  = "${aws_lb_target_group.instancea.arn_suffix}"
alb1_tg  = "${aws_lb_target_group.instanceb.arn_suffix}"
alb0_name = "InstanceA"
alb1_name = "InstanceB"
alb0_dns = "${aws_lb.instancea.dns_name}"
alb1_dns = "${aws_lb.instanceb.dns_name}"

  }
}

resource "aws_cloudwatch_dashboard" "main" {
  dashboard_name = "${var.project_key}_${var.region}"
  dashboard_body = "${data.template_file.widgets.rendered}"
}
