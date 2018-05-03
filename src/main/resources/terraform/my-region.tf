module "{{region}}" {
  project      = "${var.project}"
  project_key  = "${var.project_key}"
  project_name = "${var.project_name}"
  subscription = "${var.subscription}"
  ligoj_url    = "${var.ligoj_url}"

  source = "./{{region}}"
  region = "{{region}}"

  it              = "${var.it}"
  key_name        = "${var.key_name}"
  public_key      = "${var.public_key}"
  access_key      = "${var.access_key}"
  secret_key      = "${var.secret_key}"
  tags            = "${local.tags}"
  azs             = "${var.azs}"
  cidr            = "${var.cidr}"
  private_subnets = "${var.private_subnets}"
  public_subnets  = "${var.public_subnets}"
  ingress         = "${var.ingress}"
  ingress-elb     = "${var.ingress-elb}"
}
