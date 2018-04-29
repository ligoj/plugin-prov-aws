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
{{references}}
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
{{references}}

  }
}

resource "aws_cloudwatch_dashboard" "main" {
  dashboard_name = "${var.project_key}_${var.region}"
  dashboard_body = "${data.template_file.widgets.rendered}"
}
