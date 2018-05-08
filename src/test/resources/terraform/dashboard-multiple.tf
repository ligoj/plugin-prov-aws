locals {
  period         = "${var.period == 0 ? (var.it ? 60 * 60 : (60 * 60 * 24)) : var.period}"
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

ec20 = "${aws_instance.instanceec21.id}"
ec21 = "${aws_instance.instanceec22.id}"
ec20_name = "InstanceEC21"
ec21_name = "InstanceEC22"
ec20_ip = "${aws_instance.instanceec21.public_ip}"
ec21_ip = "${aws_instance.instanceec22.public_ip}"
spot0       = "${aws_spot_instance_request.instancespot1.id}"
spot1       = "${aws_spot_instance_request.instancespot2.id}"
spot0_name = "InstanceSpot1"
spot1_name = "InstanceSpot2"
spot0_price = "0.1"
spot1_price = "0.1"
asg0     = "${aws_autoscaling_group.instanceas1.name}"
asg1     = "${aws_autoscaling_group.instanceas2.name}"
asg0_name = "InstanceAS1"
asg1_name = "InstanceAS2"
alb0     = "${aws_lb.instanceas1.arn_suffix}"
alb1     = "${aws_lb.instanceas2.arn_suffix}"
alb0_tg  = "${aws_lb_target_group.instanceas1.arn_suffix}"
alb1_tg  = "${aws_lb_target_group.instanceas2.arn_suffix}"
alb0_name = "InstanceAS1"
alb1_name = "InstanceAS2"
alb0_dns = "${aws_lb.instanceas1.dns_name}"
alb1_dns = "${aws_lb.instanceas2.dns_name}"
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

ec20 = "${aws_instance.instanceec21.id}"
ec21 = "${aws_instance.instanceec22.id}"
ec20_name = "InstanceEC21"
ec21_name = "InstanceEC22"
ec20_ip = "${aws_instance.instanceec21.public_ip}"
ec21_ip = "${aws_instance.instanceec22.public_ip}"
spot0       = "${aws_spot_instance_request.instancespot1.id}"
spot1       = "${aws_spot_instance_request.instancespot2.id}"
spot0_name = "InstanceSpot1"
spot1_name = "InstanceSpot2"
spot0_price = "0.1"
spot1_price = "0.1"
asg0     = "${aws_autoscaling_group.instanceas1.name}"
asg1     = "${aws_autoscaling_group.instanceas2.name}"
asg0_name = "InstanceAS1"
asg1_name = "InstanceAS2"
alb0     = "${aws_lb.instanceas1.arn_suffix}"
alb1     = "${aws_lb.instanceas2.arn_suffix}"
alb0_tg  = "${aws_lb_target_group.instanceas1.arn_suffix}"
alb1_tg  = "${aws_lb_target_group.instanceas2.arn_suffix}"
alb0_name = "InstanceAS1"
alb1_name = "InstanceAS2"
alb0_dns = "${aws_lb.instanceas1.dns_name}"
alb1_dns = "${aws_lb.instanceas2.dns_name}"

  }
}

resource "aws_cloudwatch_dashboard" "main" {
  dashboard_name = "${var.project_key}_${var.region}"
  dashboard_body = "${data.template_file.widgets.rendered}"
}
