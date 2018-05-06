module "ami_{{name}}" {
  source = "git::https://github.com/fabdouglas/terraform-aws-ami-search?ref=primary-owners"
  os     = "{{name}}"
}
