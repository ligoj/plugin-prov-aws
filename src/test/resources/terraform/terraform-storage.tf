provider "aws" {
  region = "eu-west-1"
}
resource "aws_ebs_volume" "backup" {
  availability_zone = "eu-west-1a"
  type = "gp2"
  size = 40
  tags = {
    Project = "gStack"
    Name = "gStack-backup"
  }
}
