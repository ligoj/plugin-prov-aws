variable "AWS_ACCESS_KEY_ID" {}
variable "AWS_SECRET_ACCESS_KEY" {}
provider "aws" {
  region = "eu-west-1"
  access_key = "${var.AWS_ACCESS_KEY_ID}"
  secret_key = "${var.AWS_SECRET_ACCESS_KEY}"
}
resource "aws_ebs_volume" "backup" {
  availability_zone = "eu-west-1a"
  type = "gp2"
  size = 40
  tags = {
    Project = "Jupiter"
    Name = "Jupiter-backup"
  }
}
