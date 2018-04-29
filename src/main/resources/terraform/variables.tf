variable "access_key" {}
variable "secret_key" {}
variable "subscription" {}
variable "project" {}
variable "project_key" {}
variable "project_name" {}
variable "ligoj_url" {}

variable "it" {
  default = true
}

variable "public_key" {}

variable "azs" {
  default = ["a"] # ["a","b","c"]
}

variable cidr {
  default = "10.0.0.0/16"
}

variable private_subnets {
  default = ["10.0.1.0/24"] #, "10.0.2.0/24", "10.0.3.0/24"]
}

variable public_subnets {
  default = ["10.0.101.0/24"] #, "10.0.102.0/24", "10.0.103.0/24"]
}

variable "key_name" {
  description = "Desired name of AWS key pair"
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
