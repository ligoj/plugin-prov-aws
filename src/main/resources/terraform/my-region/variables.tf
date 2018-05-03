variable "access_key" {}
variable "secret_key" {}
variable "key_name" {}
variable "public_key" {}
variable "region" {}
variable "project" {}
variable "project_key" {}
variable "project_name" {}
variable "subscription" {}
variable "ligoj_url" {}

variable "it" {
  default = true
}

variable period {
  default = 0
}

variable "tags" {
  default = {}
}

variable "ingress" {
  default = {}
}

variable "ingress-elb" {
  default = {}
}

variable "servers_per_az" {
  default = 1
}

variable "azs" {
  type = "list"
}

variable cidr {}

variable private_subnets {
  type = "list"
}

variable public_subnets {
  type = "list"
}

variable scale_out_cooldown {
  default = 300
}

variable scale_in_cooldown {
  default = 300
}

variable scale_out_period {
  default = 60
}

variable scale_in_period {
  default = 60
}

variable scale_out_threshold {
  default = 75
}

variable scale_in_threshold {
  default = 20
}

variable scale_in_evaluation_periods {
  default = 2
}

variable scale_out_evaluation_periods {
  default = 2
}

variable scale_in_scaling_adjustment {
  default = -20
}

variable scale_out_scaling_adjustment {
  default = 20
}
variable "health_check_grace_period" {
  description = "Time (in seconds) after instance comes into service before checking health"
  default     = 120
}

variable "wait_for_capacity_timeout" {
  description = "A maximum duration that Terraform should wait for ASG instances to be healthy before timing out. (See also Waiting for Capacity below.) Setting this to '0' causes Terraform to skip all Capacity Waiting behavior."
  default     = "3m"
}

variable "default_cooldown" {
  description = "The amount of time, in seconds, after a scaling activity completes before another scaling activity can start"
  default     = 60
}
variable "termination_policies" {
  description = "A list of policies to decide how the instances in the auto scale group should be terminated. The allowed values are OldestInstance, NewestInstance, OldestLaunchConfiguration, ClosestToNextInstanceHour, Default"
  type        = "list"
  default     = ["Default"]
}
variable "suspended_processes" {
  description = "A list of processes to suspend for the AutoScaling Group. The allowed values are Launch, Terminate, HealthCheck, ReplaceUnhealthy, AZRebalance, AlarmNotification, ScheduledActions, AddToLoadBalancer. Note that if you suspend either the Launch or Terminate process types, it can prevent your autoscaling group from functioning properly."
  default     = []
}
variable "enabled_metrics" {
  description = "A list of metrics to collect. The allowed values are GroupMinSize, GroupMaxSize, GroupDesiredCapacity, GroupInServiceInstances, GroupPendingInstances, GroupStandbyInstances, GroupTerminatingInstances, GroupTotalInstances"
  type        = "list"

  default = [
    "GroupMinSize",
    "GroupMaxSize",
    "GroupDesiredCapacity",
    "GroupInServiceInstances",
    "GroupPendingInstances",
    "GroupStandbyInstances",
    "GroupTerminatingInstances",
    "GroupTotalInstances",
  ]
}
variable "metrics_granularity" {
  description = "The granularity to associate with the metrics to collect. The only valid value is 1Minute"
  default     = "1Minute"
}
variable "load_balancer_update_timeout" {
  description = "Timeout value when updating the ALB."
  default     = "10m"
}
variable "load_balancer_create_timeout" {
  description = "Timeout value when creating the ALB."
  default     = "10m"
}
variable "load_balancer_delete_timeout" {
  description = "Timeout value when deleting the ALB."
  default     = "10m"
}
