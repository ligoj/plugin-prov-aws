resource "aws_launch_configuration" "instancea" {
  name_prefix                 = "instancea-"
  image_id                    = "${module.ami_amazon.ami_id}"
  instance_type               = "t2.micro"
  key_name                    = "${var.key_name}"
  security_groups             = ["${aws_security_group.default.id}"]
  enable_monitoring           = true
  ebs_optimized               = false
  root_block_device           = "${var.root_block_device}"
  spot_price                  = "${var.spot_price}"

  lifecycle {
    create_before_destroy = true
  }


root_block_device {
  volume_type   = "gp2"
  volume_size   = 10
}

ebs_block_device {
  device_name   = "/dev/sdf"
  volume_type   = "gp2"
  volume_size   = 11
}

ebs_block_device {
  device_name   = "/dev/sdg"
  volume_type   = "gp2"
  volume_size   = 12
}  user_data = <<-EOF
#!/bin/bash
yum -y update
yum -y install initscripts nginx
service nginx start

  EOF
}

resource "aws_autoscaling_group" "instancea" {
  name_prefix          = "InstanceA-"
  launch_configuration = "${aws_launch_configuration.instancea.name}"
  vpc_zone_identifier  = ["${var.subnets}"]
  max_size             = "1"
  min_size             = "2"
  desired_capacity     = "1"
  health_check_grace_period = "${var.health_check_grace_period}"
  health_check_type         = "ELB"
  min_elb_capacity          = 0
  wait_for_elb_capacity     = false
  target_group_arns         = ["${aws_lb.instancea.target_group_arns}"]
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
        map("key", "Name", "value", "InstanceA", "propagate_at_launch", true),
        map("key", "ligoj:subscription", "${var.subscription}", "InstanceA", "propagate_at_launch", true),
        map("key", "ligoj:project", "value", "${var.project}", "propagate_at_launch", true)
   )))}"]
}

resource "aws_autoscaling_policy" "instancea-out" {
  name                     = "High CPU"
  scaling_adjustment       = "${var.scale_out_scaling_adjustment}"
  adjustment_type          = "PercentChangeInCapacity"
  min_adjustment_magnitude = 1
  cooldown                 = "${var.scale_out_cooldown}"
  autoscaling_group_name   = "${module.asg-instancea.this_autoscaling_group_name}"
}

resource "aws_cloudwatch_metric_alarm" "instancea-out" {
  alarm_name          = "instancea-autoscaling_group-scale_out-cpu"
  comparison_operator = "GreaterThanOrEqualToThreshold"
  evaluation_periods  = "${var.scale_out_evaluation_periods}"
  metric_name         = "CPUUtilization"
  namespace           = "AWS/EC2"
  period              = "${var.scale_out_period}"
  statistic           = "Average"
  threshold           = "${var.scale_out_threshold}"
  alarm_description   = "${jsonencode(merge(var.tags, map("Name", "InstanceA", "ScaleType", "out")))}"
  alarm_actions       = ["${aws_autoscaling_policy.out.arn}"]

  dimensions {
    AutoScalingGroupName = "${module.asg-instancea.this_autoscaling_group_name}"
  }
}

resource "aws_autoscaling_policy" "instancea-in" {
  name                     = "Low CPU"
  scaling_adjustment       = "${var.scale_in_scaling_adjustment}"
  adjustment_type          = "PercentChangeInCapacity"
  min_adjustment_magnitude = 1
  cooldown                 = "${var.scale_in_cooldown}"
  autoscaling_group_name   = "${module.asg-instancea.this_autoscaling_group_name}"
}

resource "aws_cloudwatch_metric_alarm" "instancea-in" {
  alarm_name          = "instancea-autoscaling_group-scale_in-cpu"
  comparison_operator = "LessThanOrEqualToThreshold"
  evaluation_periods  = "${var.scale_in_evaluation_periods}"
  metric_name         = "CPUUtilization"
  namespace           = "AWS/EC2"
  period              = "${var.scale_in_period}"
  statistic           = "Average"
  threshold           = "${var.scale_in_threshold}"
  alarm_description   = "${jsonencode(merge(var.tags, map("Name", "InstanceA", "ScaleType", "in")))}"
  alarm_actions       = ["${aws_autoscaling_policy.in.arn}"]

  dimensions {
    AutoScalingGroupName = "${module.asg-instancea.this_autoscaling_group_name}"
  }
}
