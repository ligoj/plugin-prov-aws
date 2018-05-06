resource "aws_launch_configuration" "{{key}}" {
  name_prefix                 = "{{key}}-"
  image_id                    = "${module.ami_{{os}}.ami_id}"
  instance_type               = "{{type}}"
  key_name                    = "${var.key_name}"
  security_groups             = ["${aws_security_group.default.id}"]
  enable_monitoring           = true
  ebs_optimized               = false
  root_block_device           = "${var.root_block_device}"
  spot_price                  = "${var.spot_price}"

  lifecycle {
    create_before_destroy = true
  }
{{root-device}}{{ebs-devices}}{{user-data}}
}

resource "aws_autoscaling_group" "{{key}}" {
  name_prefix          = "{{name}}-"
  launch_configuration = "${aws_launch_configuration.{{key}}.name}"
  vpc_zone_identifier  = ["${var.subnets}"]
  max_size             = "{{min}}"
  min_size             = "{{max}}"
  desired_capacity     = "{{min}}"
  health_check_grace_period = "${var.health_check_grace_period}"
  health_check_type         = "ELB"
  min_elb_capacity          = 0
  wait_for_elb_capacity     = false
  target_group_arns         = ["${aws_lb.{{key}}.target_group_arns}"]
  default_cooldown          = "${var.default_cooldown}"
  force_delete              = false
  termination_policies      = "${var.termination_policies}"
  suspended_processes       = "${var.suspended_processes}"
  enabled_metrics           = ["${var.enabled_metrics}"]
  metrics_granularity       = "${var.metrics_granularity}"
  wait_for_capacity_timeout = "${var.wait_for_capacity_timeout}"
  protect_from_scale_in     = false
  tags                      = ["${distinct(concat(
      list(
        map("key", "Name", "value", "{{name}}", "propagate_at_launch", true),
        map("key", "ligoj:subscription", "${var.subscription}", "{{name}}", "propagate_at_launch", true),
        map("key", "ligoj:project", "value", "${var.project}", "propagate_at_launch", true)
   )))}"]
}

resource "aws_autoscaling_policy" "{{key}}-out" {
  name                     = "High CPU"
  scaling_adjustment       = "${var.scale_out_scaling_adjustment}"
  adjustment_type          = "PercentChangeInCapacity"
  min_adjustment_magnitude = 1
  cooldown                 = "${var.scale_out_cooldown}"
  autoscaling_group_name   = "${module.asg-{{key}}.this_autoscaling_group_name}"
}

resource "aws_cloudwatch_metric_alarm" "{{key}}-out" {
  alarm_name          = "{{key}}-autoscaling_group-scale_out-cpu"
  comparison_operator = "GreaterThanOrEqualToThreshold"
  evaluation_periods  = "${var.scale_out_evaluation_periods}"
  metric_name         = "CPUUtilization"
  namespace           = "AWS/EC2"
  period              = "${var.scale_out_period}"
  statistic           = "Average"
  threshold           = "${var.scale_out_threshold}"
  alarm_description   = "${jsonencode(merge(var.tags, map("Name", "{{name}}", "ScaleType", "out")))}"
  alarm_actions       = ["${aws_autoscaling_policy.out.arn}"]

  dimensions {
    AutoScalingGroupName = "${module.asg-{{key}}.this_autoscaling_group_name}"
  }
}

resource "aws_autoscaling_policy" "{{key}}-in" {
  name                     = "Low CPU"
  scaling_adjustment       = "${var.scale_in_scaling_adjustment}"
  adjustment_type          = "PercentChangeInCapacity"
  min_adjustment_magnitude = 1
  cooldown                 = "${var.scale_in_cooldown}"
  autoscaling_group_name   = "${module.asg-{{key}}.this_autoscaling_group_name}"
}

resource "aws_cloudwatch_metric_alarm" "{{key}}-in" {
  alarm_name          = "{{key}}-autoscaling_group-scale_in-cpu"
  comparison_operator = "LessThanOrEqualToThreshold"
  evaluation_periods  = "${var.scale_in_evaluation_periods}"
  metric_name         = "CPUUtilization"
  namespace           = "AWS/EC2"
  period              = "${var.scale_in_period}"
  statistic           = "Average"
  threshold           = "${var.scale_in_threshold}"
  alarm_description   = "${jsonencode(merge(var.tags, map("Name", "{{name}}", "ScaleType", "in")))}"
  alarm_actions       = ["${aws_autoscaling_policy.in.arn}"]

  dimensions {
    AutoScalingGroupName = "${module.asg-{{key}}.this_autoscaling_group_name}"
  }
}
