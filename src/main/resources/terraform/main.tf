# -plugin-dir /path/to/plugin-dir
terraform {
  required_version = ">= 0.11.5"
}

locals {
  tags = "${merge(var.tags, map(
    "Name", var.project_key, 
    "ligoj:subscription" , var.subscription,
    "ligoj:project" , var.project
  ))}"
  period            = "${var.it ? 60 * 60 : (60 * 60 * 24)}"
}
