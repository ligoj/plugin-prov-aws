public_key = "{{public_key}}"
key_name   = "{{key_name}}"

project = "{{project.id}}"

project_key = "{{project.pkey}}"

project_name = "{{project.name}}"

subscription = "{{subscription.id}}"

ligoj_url = "http://localhost:8080/ligoj"

it = true

tags = {}

ingress = {
  "Public" = "0.0.0.0/0 22-22 tcp" # "0.0.0.0/0,::/0 22-22 tcp"
  "HTTP"   = "0.0.0.0/0 80-80 tcp"
}

ingress-elb = {
  "Public" = "0.0.0.0/0 80-80 tcp"
}

cidr = "{{cidr}}"

azs = ["a", "b", "c"]

private_subnets = [{{private_subnets}}] #, "10.0.2.0/24", "10.0.3.0/24"]

public_subnets = [{{public_subnets}}] #, "10.0.102.0/24", "10.0.103.0/24"]
